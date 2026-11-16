package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AppointmentStatus;
import com.aaa.aihealthcare.dao.DoctorConsultationDao;
import com.aaa.aihealthcare.dao.DoctorDao;
import com.aaa.aihealthcare.dao.DoctorWorkspaceDao;
import com.aaa.aihealthcare.dao.PrescriptionDao;
import com.aaa.aihealthcare.dao.SysUserDao;
import com.aaa.aihealthcare.entity.ConsultationRecord;
import com.aaa.aihealthcare.entity.Doctor;
import com.aaa.aihealthcare.entity.Medicine;
import com.aaa.aihealthcare.entity.Prescription;
import com.aaa.aihealthcare.entity.PrescriptionItem;
import com.aaa.aihealthcare.entity.SysUser;
import com.aaa.aihealthcare.service.DoctorConsultationService;
import com.aaa.aihealthcare.service.NotificationService;
import com.aaa.aihealthcare.vo.AppointmentVO;
import com.aaa.aihealthcare.vo.ConsultationDetailVO;
import com.aaa.aihealthcare.vo.ConsultationRecordRequest;
import com.aaa.aihealthcare.vo.PrescriptionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DoctorConsultationServiceImpl implements DoctorConsultationService {

    private static final String PENDING_PICKUP = AppointmentStatus.PENDING_PICKUP;
    private static final String INVALID_DOCTOR_MESSAGE = "账号未关联有效医生，请联系管理员";
    private static final String FORBIDDEN_APPOINTMENT_MESSAGE = "预约不存在或无权访问";
    private static final BigDecimal MAX_DECIMAL_10_2 = new BigDecimal("99999999.99");

    @Autowired
    private DoctorDao doctorDao;

    @Autowired
    private DoctorWorkspaceDao doctorWorkspaceDao;

    @Autowired
    private DoctorConsultationDao doctorConsultationDao;

    @Autowired
    private PrescriptionDao prescriptionDao;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SysUserDao sysUserDao;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConsultationDetailVO getDetail(SysUser currentUser, Long appointmentId) {
        Long doctorId = requireDoctorId(currentUser);
        AppointmentVO appointment = doctorWorkspaceDao.selectAppointment(doctorId, appointmentId);
        if (appointment == null) {
            throw forbiddenAppointment();
        }

        ConsultationRecord record = doctorConsultationDao.selectRecord(doctorId, appointmentId);
        List<Prescription> prescriptions = doctorConsultationDao.selectPrescriptions(appointmentId);
        Prescription prescription = prescriptions.isEmpty() ? null : prescriptions.get(0);
        List<PrescriptionItem> items = prescription == null
                ? List.of()
                : doctorConsultationDao.selectItems(prescription.getId());

        ConsultationDetailVO detail = new ConsultationDetailVO();
        detail.setAppointment(appointment);
        detail.setRecord(record);
        detail.setPrescription(prescription);
        detail.setItems(items);
        detail.setAllowedActions(allowedActions(appointment.getStatus(), record, prescription));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(SysUser currentUser, Long appointmentId) {
        lockNotificationAccounts();
        Long doctorId = requireDoctorId(currentUser);
        AppointmentVO appointment = requireLockedAppointment(doctorId, appointmentId);
        if (AppointmentStatus.IN_CONSULTATION.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("该预约已开始就诊，请勿重复操作");
        }
        if (!AppointmentStatus.CHECKED_IN.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("当前状态为「" + AppointmentStatus.label(appointment.getStatus())
                    + "」，只有已到院预约可以开始就诊");
        }

        int updated = doctorConsultationDao.updateAppointmentStatus(
                doctorId, appointmentId, List.of(AppointmentStatus.CHECKED_IN),
                AppointmentStatus.IN_CONSULTATION);
        if (updated != 1) {
            throw new IllegalStateException("预约状态已变化，请刷新后重试");
        }
        notificationService.consultationStarted(appointmentId, appointment.getUserId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsultationRecord saveRecord(SysUser currentUser, Long appointmentId,
                                         ConsultationRecordRequest request) {
        Long doctorId = requireDoctorId(currentUser);
        AppointmentVO appointment = requireLockedAppointment(doctorId, appointmentId);
        requireRecordWritableStatus(appointment.getStatus());
        validateRecordRequest(request);

        ConsultationRecord existing = doctorConsultationDao.selectRecordForUpdate(doctorId, appointmentId);
        ConsultationRecord record = new ConsultationRecord();
        record.setAppointmentId(appointmentId);
        record.setDoctorId(doctorId);
        record.setChiefComplaint(request.getChiefComplaint());
        record.setPresentIllness(request.getPresentIllness());
        record.setDiagnosis(request.getDiagnosis());
        record.setTreatmentPlan(request.getTreatmentPlan());
        record.setMedicalAdvice(request.getMedicalAdvice());

        int changed;
        if (existing == null) {
            changed = doctorConsultationDao.insertRecord(record);
        } else {
            record.setId(existing.getId());
            changed = doctorConsultationDao.updateRecord(record);
        }
        if (changed != 1) {
            throw new IllegalStateException("病历保存失败，请刷新后重试");
        }
        return doctorConsultationDao.selectRecordForUpdate(doctorId, appointmentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Prescription prescribe(SysUser currentUser, Long appointmentId, PrescriptionRequest request) {
        lockNotificationAccounts();
        Long doctorId = requireDoctorId(currentUser);
        AppointmentVO appointment = requireLockedAppointment(doctorId, appointmentId);
        if (!AppointmentStatus.IN_CONSULTATION.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("当前状态为「" + AppointmentStatus.label(appointment.getStatus())
                    + "」，只有就诊中预约可以开处方");
        }

        ConsultationRecord record = doctorConsultationDao.selectRecordForUpdate(doctorId, appointmentId);
        if (!isValidRecord(record)) {
            throw new IllegalArgumentException("请先保存包含主诉和诊断的有效病历");
        }
        if (!doctorConsultationDao.selectPrescriptionsForUpdate(appointmentId).isEmpty()) {
            throw new IllegalArgumentException("该预约已有处方，不能重复开方");
        }

        List<ValidatedPrescriptionItem> requestedItems = validatePrescriptionItems(request);
        List<Long> medicineIds = requestedItems.stream()
                .map(ValidatedPrescriptionItem::medicineId)
                .toList();
        List<Medicine> medicines = doctorConsultationDao.selectMedicinesForUpdate(medicineIds);
        Map<Long, Medicine> medicineMap = new HashMap<>();
        for (Medicine medicine : medicines) {
            medicineMap.put(medicine.getId(), medicine);
        }
        if (medicineMap.size() != medicineIds.size()) {
            throw new IllegalArgumentException("处方中包含不存在的药品，请刷新后重试");
        }

        List<PrescriptionItem> items = new ArrayList<>(requestedItems.size());
        BigDecimal totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (ValidatedPrescriptionItem requestedItem : requestedItems) {
            Medicine medicine = medicineMap.get(requestedItem.medicineId());
            requireAvailableMedicine(medicine, requestedItem.quantity());

            BigDecimal unitPrice = money(medicine.getPrice(), "药品价格无效");
            BigDecimal amount = money(unitPrice.multiply(BigDecimal.valueOf(requestedItem.quantity())),
                    "药品明细金额超出允许范围");
            totalAmount = totalAmount.add(amount);
            if (totalAmount.compareTo(MAX_DECIMAL_10_2) > 0) {
                throw new IllegalArgumentException("处方总金额超出允许范围");
            }

            PrescriptionItem item = new PrescriptionItem();
            item.setMedicineId(medicine.getId());
            item.setMedicineName(medicine.getName());
            item.setSpec(medicine.getSpec());
            item.setDosage(requestedItem.dosage());
            item.setFrequency(requestedItem.frequency());
            item.setDays(requestedItem.days());
            item.setQuantity(requestedItem.quantity());
            item.setUnitPrice(unitPrice);
            item.setAmount(amount);
            items.add(item);
        }

        LocalDateTime now = LocalDateTime.now();
        Prescription prescription = new Prescription();
        prescription.setAppointmentId(appointmentId);
        prescription.setUserId(appointment.getUserId());
        prescription.setDoctorId(doctorId);
        prescription.setTotalAmount(totalAmount);
        prescription.setStatus(AppointmentStatus.RX_UNPAID);
        prescription.setCreateTime(now);
        prescription.setUpdateTime(now);
        if (prescriptionDao.insert(prescription) != 1 || prescription.getId() == null) {
            throw new IllegalStateException("处方保存失败，请重试");
        }

        for (PrescriptionItem item : items) {
            item.setPrescriptionId(prescription.getId());
            if (doctorConsultationDao.insertItem(item) != 1) {
                throw new IllegalStateException("处方明细保存失败，请重试");
            }
        }

        int updated = doctorConsultationDao.updateAppointmentStatus(
                doctorId, appointmentId, List.of(AppointmentStatus.IN_CONSULTATION),
                AppointmentStatus.PENDING_RX_PAY);
        if (updated != 1) {
            throw new IllegalStateException("预约状态已变化，处方已撤销");
        }
        notificationService.prescriptionCreated(appointmentId, appointment.getUserId());
        return prescription;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(SysUser currentUser, Long appointmentId) {
        lockNotificationAccounts();
        Long doctorId = requireDoctorId(currentUser);
        AppointmentVO appointment = requireLockedAppointment(doctorId, appointmentId);
        if (AppointmentStatus.COMPLETED.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("该预约已完成，请勿重复操作");
        }
        if (!AppointmentStatus.IN_CONSULTATION.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("当前状态为「" + AppointmentStatus.label(appointment.getStatus())
                    + "」，只有就诊中预约可以完成");
        }

        ConsultationRecord record = doctorConsultationDao.selectRecordForUpdate(doctorId, appointmentId);
        if (!isValidRecord(record)) {
            throw new IllegalArgumentException("请先保存包含主诉和诊断的有效病历");
        }
        if (!doctorConsultationDao.selectPrescriptionsForUpdate(appointmentId).isEmpty()) {
            throw new IllegalArgumentException("该预约已有处方，请缴费并完成发药后再结束诊疗");
        }

        int updated = doctorConsultationDao.updateAppointmentStatus(
                doctorId, appointmentId, List.of(AppointmentStatus.IN_CONSULTATION),
                AppointmentStatus.COMPLETED);
        if (updated != 1) {
            throw new IllegalStateException("预约状态已变化，请刷新后重试");
        }
        notificationService.appointmentCompleted(appointmentId, appointment.getUserId(), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispense(SysUser currentUser, Long appointmentId) {
        // 医生端发药入口已下线；仅保留 ADMIN 兜底与配药师通道。
        throw new UnsupportedOperationException("医生端已不再提供发药操作，请由配药师发药");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispenseForAdmin(Long prescriptionId) {
        lockNotificationAccounts();
        Prescription prescription = prescriptionDao.selectById(prescriptionId);
        if (prescription == null) {
            throw new IllegalArgumentException("处方不存在");
        }
        dispenseLocked(prescription.getDoctorId(), prescription.getAppointmentId(), prescriptionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispenseAsPharmacist(Long prescriptionId) {
        lockNotificationAccounts();
        Prescription prescription = prescriptionDao.selectById(prescriptionId);
        if (prescription == null) {
            throw new IllegalArgumentException("处方不存在");
        }
        // 药师通道：doctorId 传 null，跳过开方医生归属校验，但保留全部业务校验。
        dispenseLocked(null, prescription.getAppointmentId(), prescriptionId);
    }

    private void dispenseLocked(Long doctorId, Long appointmentId, Long expectedPrescriptionId) {
        AppointmentVO appointment = requireLockedAppointment(doctorId, appointmentId);
        if (AppointmentStatus.COMPLETED.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("该预约已完成，请勿重复发药");
        }
        if (!AppointmentStatus.PENDING_RX_PAY.equals(appointment.getStatus())
                && !PENDING_PICKUP.equals(appointment.getStatus())) {
            throw new IllegalArgumentException("当前状态为「" + AppointmentStatus.label(appointment.getStatus())
                    + "」，不能发药");
        }

        List<Prescription> prescriptions = doctorConsultationDao.selectPrescriptionsForUpdate(appointmentId);
        if (prescriptions.isEmpty()) {
            throw new IllegalArgumentException("该预约没有可发药的处方");
        }
        if (prescriptions.size() != 1) {
            throw new IllegalArgumentException("该预约存在多张处方，无法发药");
        }
        Prescription prescription = prescriptions.get(0);
        if (expectedPrescriptionId != null && !expectedPrescriptionId.equals(prescription.getId())) {
            throw new IllegalArgumentException("处方与预约不匹配");
        }
        if (AppointmentStatus.RX_DISPENSED.equals(prescription.getStatus())) {
            throw new IllegalArgumentException("该处方已发药，请勿重复操作");
        }
        if (!AppointmentStatus.RX_PAID.equals(prescription.getStatus())) {
            throw new IllegalArgumentException("处方尚未缴费，不能发药");
        }
        // doctorId 为 null 表示配药师发药，不校验开方医生归属；
        // 预约与患者归属校验在两种通道下都必须成立。
        if ((doctorId != null && !doctorId.equals(prescription.getDoctorId()))
                || !appointment.getUserId().equals(prescription.getUserId())
                || !appointmentId.equals(prescription.getAppointmentId())) {
            throw new IllegalStateException("处方归属信息异常，无法发药");
        }

        List<PrescriptionItem> items = doctorConsultationDao.selectItemsForUpdate(prescription.getId());
        if (items.isEmpty()) {
            throw new IllegalStateException("处方没有明细，无法发药");
        }
        Set<Long> medicineIds = new HashSet<>();
        for (PrescriptionItem item : items) {
            if (item.getMedicineId() == null || item.getQuantity() == null
                    || item.getQuantity() < 1 || item.getQuantity() > 1000) {
                throw new IllegalStateException("处方明细数据异常，无法发药");
            }
            if (!medicineIds.add(item.getMedicineId())) {
                throw new IllegalStateException("处方包含重复药品，无法发药");
            }
        }

        List<PrescriptionItem> sortedItems = new ArrayList<>(items);
        sortedItems.sort(Comparator.comparing(PrescriptionItem::getMedicineId));
        List<Long> sortedMedicineIds = sortedItems.stream()
                .map(PrescriptionItem::getMedicineId)
                .toList();
        List<Medicine> medicines = doctorConsultationDao.selectMedicinesForUpdate(sortedMedicineIds);
        Map<Long, Medicine> medicineMap = new HashMap<>();
        for (Medicine medicine : medicines) {
            medicineMap.put(medicine.getId(), medicine);
        }
        if (medicineMap.size() != sortedMedicineIds.size()) {
            throw new IllegalArgumentException("处方中的药品已缺失，无法发药");
        }

        for (PrescriptionItem item : sortedItems) {
            Medicine medicine = medicineMap.get(item.getMedicineId());
            requireAvailableMedicine(medicine, item.getQuantity());
        }
        for (PrescriptionItem item : sortedItems) {
            int deducted = doctorConsultationDao.deductMedicineStock(item.getMedicineId(), item.getQuantity());
            if (deducted != 1) {
                throw new IllegalArgumentException("药品库存不足或已停用，发药失败");
            }
        }

        // 药师通道（doctorId == null）使用不限定开方医生的更新语句
        int prescriptionUpdated = doctorId == null
                ? doctorConsultationDao.updatePrescriptionToDispensedForPharmacy(
                        prescription.getId(), appointmentId, appointment.getUserId())
                : doctorConsultationDao.updatePrescriptionToDispensed(
                        prescription.getId(), appointmentId, appointment.getUserId(), doctorId);
        if (prescriptionUpdated != 1) {
            throw new IllegalStateException("处方状态已变化，发药失败");
        }
        int appointmentUpdated = doctorId == null
                ? doctorConsultationDao.updateAppointmentStatusForPharmacy(
                        appointmentId,
                        List.of(AppointmentStatus.PENDING_RX_PAY, PENDING_PICKUP),
                        AppointmentStatus.COMPLETED)
                : doctorConsultationDao.updateAppointmentStatus(
                        doctorId, appointmentId,
                        List.of(AppointmentStatus.PENDING_RX_PAY, PENDING_PICKUP),
                        AppointmentStatus.COMPLETED);
        if (appointmentUpdated != 1) {
            throw new IllegalStateException("预约状态已变化，发药失败");
        }
        notificationService.appointmentCompleted(appointmentId, appointment.getUserId(), true);
        notificationService.prescriptionDispensed(prescription.getId());
    }

    private void lockNotificationAccounts() {
        // 通知的 INSERT SELECT 会读取账号行；沿用管理员写入的共同锁序，先账号再预约。
        sysUserDao.lockActiveAdminIds();
    }

    private Long requireDoctorId(SysUser currentUser) {
        Long doctorId = currentUser == null ? null : currentUser.getDoctorId();
        if (doctorId == null) {
            throw invalidDoctor();
        }
        Doctor doctor = doctorDao.selectById(doctorId);
        if (doctor == null || !Integer.valueOf(1).equals(doctor.getStatus())) {
            throw invalidDoctor();
        }
        return doctorId;
    }

    private AppointmentVO requireLockedAppointment(Long doctorId, Long appointmentId) {
        // doctorId 为 null 表示配药师通道：不限定开方医生，仅按预约 id 加锁查询
        AppointmentVO appointment = doctorId == null
                ? doctorConsultationDao.selectAppointmentForUpdateByPharmacy(appointmentId)
                : doctorConsultationDao.selectAppointmentForUpdate(doctorId, appointmentId);
        if (appointment == null) {
            throw forbiddenAppointment();
        }
        return appointment;
    }

    private void requireRecordWritableStatus(String status) {
        if (AppointmentStatus.IN_CONSULTATION.equals(status)
                || AppointmentStatus.PENDING_RX_PAY.equals(status)
                || PENDING_PICKUP.equals(status)) {
            return;
        }
        if (AppointmentStatus.COMPLETED.equals(status) || AppointmentStatus.CANCELLED.equals(status)) {
            throw new IllegalArgumentException("当前状态为「" + AppointmentStatus.label(status)
                    + "」，不允许修改病历");
        }
        throw new IllegalArgumentException("当前状态为「" + AppointmentStatus.label(status)
                + "」，请先开始就诊");
    }

    private void validateRecordRequest(ConsultationRecordRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("病历内容不能为空");
        }
        requireText(request.getChiefComplaint(), 500, "主诉");
        optionalText(request.getPresentIllness(), 1000, "现病史");
        requireText(request.getDiagnosis(), 500, "诊断");
        optionalText(request.getTreatmentPlan(), 1000, "治疗方案");
        optionalText(request.getMedicalAdvice(), 500, "医嘱");
    }

    private List<ValidatedPrescriptionItem> validatePrescriptionItems(PrescriptionRequest request) {
        if (request == null || request.getItems() == null) {
            throw new IllegalArgumentException("处方至少需要一项药品");
        }
        if (request.getItems().size() < 1 || request.getItems().size() > 50) {
            throw new IllegalArgumentException("处方药品数量必须为 1 到 50 项");
        }

        Set<Long> medicineIds = new HashSet<>();
        List<ValidatedPrescriptionItem> items = new ArrayList<>(request.getItems().size());
        for (PrescriptionRequest.Item item : request.getItems()) {
            if (item == null || item.getMedicineId() == null || item.getMedicineId() <= 0) {
                throw new IllegalArgumentException("药品 id 不能为空");
            }
            if (!medicineIds.add(item.getMedicineId())) {
                throw new IllegalArgumentException("处方中不能重复选择同一药品");
            }
            String dosage = requiredTrimmedText(item.getDosage(), 50, "用法用量");
            String frequency = requiredTrimmedText(item.getFrequency(), 50, "用药频次");
            if (item.getDays() != null && (item.getDays() < 1 || item.getDays() > 365)) {
                throw new IllegalArgumentException("用药天数必须为 1 到 365 天或留空");
            }
            if (item.getQuantity() == null || item.getQuantity() < 1 || item.getQuantity() > 1000) {
                throw new IllegalArgumentException("药品数量必须为 1 到 1000");
            }
            items.add(new ValidatedPrescriptionItem(
                    item.getMedicineId(), dosage, frequency, item.getDays(), item.getQuantity()));
        }
        items.sort(Comparator.comparing(ValidatedPrescriptionItem::medicineId));
        return items;
    }

    private void requireAvailableMedicine(Medicine medicine, Integer quantity) {
        if (medicine == null || !Integer.valueOf(1).equals(medicine.getStatus())) {
            throw new IllegalArgumentException("处方药品不存在或已停用");
        }
        if (medicine.getStock() == null || medicine.getStock() < quantity) {
            throw new IllegalArgumentException("药品「" + medicine.getName() + "」库存不足");
        }
    }

    private BigDecimal money(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(message);
        }
        BigDecimal amount = value.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(MAX_DECIMAL_10_2) > 0) {
            throw new IllegalArgumentException(message);
        }
        return amount;
    }

    private boolean isValidRecord(ConsultationRecord record) {
        return record != null
                && hasText(record.getChiefComplaint())
                && codePointLength(record.getChiefComplaint()) <= 500
                && hasText(record.getDiagnosis())
                && codePointLength(record.getDiagnosis()) <= 500
                && withinOptionalLength(record.getPresentIllness(), 1000)
                && withinOptionalLength(record.getTreatmentPlan(), 1000)
                && withinOptionalLength(record.getMedicalAdvice(), 500);
    }

    private List<String> allowedActions(String status, ConsultationRecord record, Prescription prescription) {
        if (AppointmentStatus.CHECKED_IN.equals(status)) {
            return List.of("start");
        }
        if (AppointmentStatus.IN_CONSULTATION.equals(status)) {
            List<String> actions = new ArrayList<>();
            actions.add("saveRecord");
            if (isValidRecord(record) && prescription == null) {
                actions.add("prescribe");
                actions.add("complete");
            }
            return List.copyOf(actions);
        }
        if (AppointmentStatus.PENDING_RX_PAY.equals(status) || PENDING_PICKUP.equals(status)) {
            // 发药已改由配药师在配药工作台处理，医生端只保留查看病历。
            return List.of("saveRecord");
        }
        return List.of();
    }

    private void requireText(String value, int maxLength, String label) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (codePointLength(value) > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        }
    }

    private String requiredTrimmedText(String value, int maxLength, String label) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String trimmed = value.strip();
        if (codePointLength(trimmed) > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    private void optionalText(String value, int maxLength, String label) {
        if (value != null && codePointLength(value) > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        }
    }

    private boolean withinOptionalLength(String value, int maxLength) {
        return value == null || codePointLength(value) <= maxLength;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private ResponseStatusException invalidDoctor() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, INVALID_DOCTOR_MESSAGE);
    }

    private ResponseStatusException forbiddenAppointment() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, FORBIDDEN_APPOINTMENT_MESSAGE);
    }

    private record ValidatedPrescriptionItem(Long medicineId, String dosage, String frequency,
                                             Integer days, Integer quantity) {
    }
}
