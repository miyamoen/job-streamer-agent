package net.unit8.job_streamer.agent.listener;

import clojure.java.api.Clojure;
import clojure.lang.*;
import net.unit8.job_streamer.agent.jpa.ApplicationPersistenceProviderResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.batch.api.listener.AbstractJobListener;
import javax.batch.runtime.context.JobContext;
import javax.inject.Inject;
import javax.persistence.spi.PersistenceProviderResolverHolder;
import java.util.HashMap;

import net.unit8.job_streamer.agent.util.SystemUtil;

/**
 * @author kawasima
 */
public class JobProgressListener extends AbstractJobListener {
    private static final Logger logger = LoggerFactory.getLogger(JobProgressListener.class);

    @Inject
    private JobContext jobContext;

    @Override
    public void beforeJob() {
        PersistenceProviderResolverHolder.setPersistenceProviderResolver(new ApplicationPersistenceProviderResolver());
    }

    @Override
    public void afterJob() {
        logger.debug("Send progress message... " + jobContext.getExecutionId());

        Object system = SystemUtil.getSystem();
        logger.debug("system:" + system);
        Object connector = RT.get(system, Keyword.intern("connector"));
        logger.debug("connector:" + connector);

        IFn sendMessage = Clojure.var("job-streamer.agent.component.connector", "send-message");

        PersistentHashMap commandMap = PersistentHashMap.create(
                Keyword.intern("command"), Keyword.intern("progress"),
                Keyword.intern("id"), Long.parseLong(jobContext.getProperties().getProperty("request-id")),
                Keyword.intern("execution-id"), jobContext.getExecutionId());
        sendMessage.invoke(connector, commandMap);
        logger.debug("Sent progress message:" + commandMap);
    }
}
