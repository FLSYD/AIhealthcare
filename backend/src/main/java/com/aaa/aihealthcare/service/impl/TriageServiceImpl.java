package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.dao.PatientQueryDao;
import com.aaa.aihealthcare.entity.Department;
import com.aaa.aihealthcare.service.TriageService;
import com.aaa.aihealthcare.vo.TriageDepartmentVO;
import com.aaa.aihealthcare.vo.TriageResponseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TriageServiceImpl implements TriageService {
    private static final int MAX_RESULTS = 3;
    private static final String SOURCE = "fallback";
    private static final List<TriageRule> RULES = List.of(
            new TriageRule(List.of("头痛", "头晕", "失眠", "麻木"),
                    List.of("神经", "内科", "全科"),
                    List.of("头痛", "头晕", "肢体麻木")),
            new TriageRule(List.of("发热", "咳嗽", "感冒", "发烧", "咽痛"),
                    List.of("呼吸", "内科", "传染", "全科"),
                    List.of("发热", "咳嗽", "咽痛")),
            new TriageRule(List.of("腹痛", "腹泻", "恶心", "胃"),
                    List.of("消化", "内科", "外科", "全科"),
                    List.of("腹痛", "腹泻", "消化不良")),
            new TriageRule(List.of("骨折", "关节", "腰痛", "扭伤"),
                    List.of("骨科", "外科", "康复"),
                    List.of("关节疼痛", "活动受限", "腰痛")),
            new TriageRule(List.of("视力", "眼睛", "眼干"),
                    List.of("眼科"),
                    List.of("视力下降", "眼干", "视物模糊")),
            new TriageRule(List.of("皮疹", "瘙痒", "过敏"),
                    List.of("皮肤", "儿科"),
                    List.of("皮疹", "瘙痒", "过敏")),
            new TriageRule(List.of("牙", "口腔"),
                    List.of("口腔"),
                    List.of("牙痛", "牙龈出血")),
            new TriageRule(List.of("尿频", "尿痛"),
                    List.of("泌尿"),
                    List.of("尿频", "尿痛", "排尿不适"))
    );
    private static final List<String> URGENT_KEYWORDS = List.of(
            "胸痛", "呼吸困难", "意识不清", "昏迷", "大出血",
            "抽搐", "剧烈头痛", "晕厥", "口角歪斜", "说话不清");
    private static final List<String> URGENT_SIGNS = List.of(
            "急症症状", "症状可能快速进展", "需要及时评估");

    @Autowired
    private PatientQueryDao patientQueryDao;

    @Override
    public TriageResponseVO analyze(String symptom) {
        String text = validateSymptom(symptom);
        List<Department> departments = patientQueryDao.selectActiveDepartments();
        boolean urgent = URGENT_KEYWORDS.stream().anyMatch(text::contains);
        Map<Long, Candidate> candidates = matchCandidates(text, departments);
        Set<String> commonSigns = matchedSigns(text);
        boolean matched = !candidates.isEmpty();
        if (urgent) {
            // 急症只推荐现有急诊科，目录缺失时仍保留急诊建议，不回退为普通门诊优先。
            candidates.clear();
            addUrgentCandidate(candidates, departments);
            commonSigns.addAll(URGENT_SIGNS);
        }
        if (candidates.isEmpty() && !urgent) {
            addDefaultCandidates(candidates, departments);
        }
        List<Candidate> ranked = rank(candidates.values());
        Set<String> observedKeywords = new LinkedHashSet<>();
        for (TriageRule rule : RULES) {
            rule.keywords().stream().filter(text::contains).forEach(observedKeywords::add);
        }
        URGENT_KEYWORDS.stream().filter(text::contains).forEach(observedKeywords::add);
        return buildResponse(urgent, matched, observedKeywords, commonSigns, ranked);
    }

    private String validateSymptom(String symptom) {
        String text = symptom == null ? "" : symptom.trim();
        if (text.isEmpty() || text.length() > 200) {
            throw new IllegalArgumentException("症状描述长度应为1-200个字符");
        }
        return text;
    }

    private Map<Long, Candidate> matchCandidates(String text, List<Department> departments) {
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        for (TriageRule rule : RULES) {
            if (rule.keywords().stream().noneMatch(text::contains)) {
                continue;
            }
            for (int aliasIndex = 0; aliasIndex < rule.departmentAliases().size(); aliasIndex++) {
                String alias = rule.departmentAliases().get(aliasIndex);
                int score = Math.max(1, 4 - aliasIndex);
                for (Department department : departments) {
                    if (department.getName() != null && department.getName().contains(alias)) {
                        candidates.computeIfAbsent(department.getId(), ignored ->
                                        new Candidate(department))
                                .add(score, rule.symptoms());
                    }
                }
            }
        }
        return candidates;
    }

    private Set<String> matchedSigns(String text) {
        Set<String> signs = new LinkedHashSet<>();
        for (TriageRule rule : RULES) {
            if (rule.keywords().stream().anyMatch(text::contains)) {
                signs.addAll(rule.symptoms());
            }
        }
        return signs;
    }

    private void addUrgentCandidate(Map<Long, Candidate> candidates, List<Department> departments) {
        departments.stream()
                .filter(department -> department.getName() != null
                        && department.getName().contains("急诊"))
                .findFirst()
                .ifPresent(department -> candidates.computeIfAbsent(department.getId(),
                                ignored -> new Candidate(department))
                        .add(10, URGENT_SIGNS));
    }

    private void addDefaultCandidates(Map<Long, Candidate> candidates,
                                      List<Department> departments) {
        List<Department> ordered = new ArrayList<>();
        addDepartmentsByName(ordered, departments, "全科");
        addDepartmentsByName(ordered, departments, "内科");
        addDepartmentsByName(ordered, departments, "急诊");
        for (Department department : departments) {
            if (!ordered.contains(department)) {
                ordered.add(department);
            }
        }
        int score = 3;
        for (Department department : ordered) {
            candidates.computeIfAbsent(department.getId(), ignored -> new Candidate(department))
                    .add(score--, List.of("综合不适"));
            if (score < 1) {
                break;
            }
        }
    }

    private void addDepartmentsByName(List<Department> target, List<Department> departments,
                                      String name) {
        departments.stream()
                .filter(department -> department.getName() != null
                        && department.getName().contains(name))
                .filter(department -> !target.contains(department))
                .forEach(target::add);
    }

    private List<Candidate> rank(Iterable<Candidate> candidates) {
        List<Candidate> result = new ArrayList<>();
        candidates.forEach(result::add);
        result.sort(Comparator.comparingInt(Candidate::getScore).reversed()
                .thenComparing(candidate -> candidate.getDepartment().getId()));
        return result.stream().limit(MAX_RESULTS).toList();
    }

    private TriageResponseVO buildResponse(boolean urgent, boolean matched, Set<String> observedKeywords,
                                           Set<String> commonSigns,
                                           List<Candidate> candidates) {
        List<TriageDepartmentVO> departments = candidates.stream()
                .map(this::toDepartmentVO)
                .toList();
        String departmentNames = departments.stream()
                .map(TriageDepartmentVO::getName)
                .reduce((left, right) -> left + "、" + right)
                .orElse("线下门诊");
        String summary = urgent
                ? "症状描述中包含需要优先就医的风险线索。"
                : matched
                ? "已根据内置关键词规则完成初步分诊。"
                : "暂未命中明确分诊规则，建议先咨询全科或内科。";
        String symptomAnalysis = observedKeywords.isEmpty()
                ? "当前描述未提取到明确的规则症状词。"
                : "描述中识别到：" + String.join("、", observedKeywords) + "。";
        String conclusion = urgent
                ? "建议优先前往急诊，请勿等待普通门诊预约；该结果不是疾病诊断。"
                : "建议优先咨询" + departmentNames + "；该结果只用于选择就诊科室，不是疾病诊断。";
        String advice = urgent
                ? "如胸痛、呼吸困难、意识障碍、大出血或抽搐等症状持续或加重，请立即拨打120或前往急诊，不要等待线上结果。"
                : "请结合症状持续时间、体温和既往病史及时线下就医；如症状加重或出现急症表现，请立即前往急诊。";
        TriageResponseVO response = new TriageResponseVO();
        response.setSummary(summary);
        response.setSymptomAnalysis(symptomAnalysis);
        response.setCommonSigns(new ArrayList<>(commonSigns));
        response.setPreliminaryConclusion(conclusion);
        response.setAdvice(advice + " 本结果仅供就诊建议，不能替代医生诊断。");
        response.setUrgent(urgent);
        response.setSource(SOURCE);
        response.setDepartments(departments);
        return response;
    }

    private TriageDepartmentVO toDepartmentVO(Candidate candidate) {
        Department department = candidate.getDepartment();
        TriageDepartmentVO result = new TriageDepartmentVO();
        result.setId(department.getId());
        result.setName(department.getName());
        result.setDescription(department.getDescription());
        result.setScore(candidate.getScore());
        result.setSymptoms(new ArrayList<>(candidate.getSymptoms()));
        return result;
    }

    private record TriageRule(List<String> keywords, List<String> departmentAliases,
                              List<String> symptoms) {
    }

    private static final class Candidate {
        private final Department department;
        private final Set<String> symptoms = new LinkedHashSet<>();
        private int score;

        private Candidate(Department department) {
            this.department = department;
        }

        private void add(int addedScore, List<String> addedSymptoms) {
            score = Math.min(10, score + addedScore);
            symptoms.addAll(addedSymptoms);
        }

        private Department getDepartment() {
            return department;
        }

        private Set<String> getSymptoms() {
            return symptoms;
        }

        private int getScore() {
            return score;
        }
    }
}
