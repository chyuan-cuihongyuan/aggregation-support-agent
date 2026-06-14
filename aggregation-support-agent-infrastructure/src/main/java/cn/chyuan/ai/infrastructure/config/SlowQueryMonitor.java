package cn.chyuan.ai.infrastructure.config;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@Intercepts({
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class SlowQueryMonitor implements Interceptor {
    
    private static final Logger log = LoggerFactory.getLogger(SlowQueryMonitor.class);
    private static final long SLOW_THRESHOLD_MS = 500;
    private static final long VERY_SLOW_THRESHOLD_MS = 2000;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long duration = System.currentTimeMillis() - start;
            if (duration > VERY_SLOW_THRESHOLD_MS) {
                MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
                log.error("Very slow SQL query: {}ms, mapper: {}", duration, ms.getId());
            } else if (duration > SLOW_THRESHOLD_MS) {
                MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
                log.warn("Slow SQL query: {}ms, mapper: {}", duration, ms.getId());
            }
        }
    }
}
