package com.aaa.aihealthcare.service.impl;

import com.aaa.aihealthcare.common.AvatarStorage;
import com.aaa.aihealthcare.common.Result;
import com.aaa.aihealthcare.dao.SysUserDao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 账号头像写入的统一入口：事务内先取启用管理员集合SQL锁，再取头像JVM锁，
 * 与头像回收、人员写入的锁序一致，避免“JVM锁 -> 用户行UPDATE”与回收反向等待。
 */
@Component
public class AvatarWriteExecutor {
    private final SysUserDao accounts;
    private final TransactionTemplate transactions;

    public AvatarWriteExecutor(SysUserDao accounts, PlatformTransactionManager manager) {
        this.accounts = accounts;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * 动作返回非成功结果时回滚，仅在确认回滚后清理本次文件。
     * 提交结果未知或没有完成通知时保留文件，由回收器核对引用后处理。
     */
    public Result write(Supplier<Result> action, Runnable cleanup, String failureMessage) {
        try {
            Result result = transactions.execute(status -> {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int completion) {
                            if (completion == STATUS_ROLLED_BACK) {
                                try { cleanup.run(); }
                                catch (RuntimeException ignored) {
                                    // 清理失败不覆盖事务结果，文件交给后续回收。
                                }
                            }
                        }
                    });
                }
                accounts.lockActiveAdminIds();
                Result current;
                synchronized (AvatarStorage.LOCK) {
                    current = action.get();
                }
                if (current == null || current.getCode() != 200) {
                    status.setRollbackOnly();
                }
                return current;
            });
            return result == null ? Result.error(failureMessage) : result;
        } catch (RuntimeException exception) {
            // 抛异常不能证明数据库已回滚，尤其不能据此删除可能已提交引用的文件。
            return Result.error(failureMessage);
        }
    }
}
