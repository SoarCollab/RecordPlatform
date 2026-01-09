package cn.flying.test.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@SuppressWarnings({"rawtypes", "unchecked"})
public class LogbackSilencerExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(LogbackSilencerExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        Set<String> loggerPrefixes = collectLoggerPrefixes(context);
        if (loggerPrefixes.isEmpty()) {
            return;
        }

        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext loggerContext)) {
            return;
        }

        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);

        List<AppenderFilterSnapshot> snapshots = new ArrayList<>();
        for (var it = rootLogger.iteratorForAppenders(); it.hasNext(); ) {
            Appender<ILoggingEvent> appender = (Appender<ILoggingEvent>) it.next();

            List<Filter<ILoggingEvent>> originalFilters = appender.getCopyOfAttachedFiltersList();
            appender.clearAllFilters();

            Filter<ILoggingEvent> installedFilter = new DenyLoggerFilter(loggerPrefixes);
            installedFilter.start();
            appender.addFilter(installedFilter);

            for (Filter<ILoggingEvent> original : originalFilters) {
                appender.addFilter(original);
            }

            snapshots.add(new AppenderFilterSnapshot(appender, originalFilters, installedFilter));
        }

        ExtensionContext.Store store = context.getStore(NAMESPACE);
        store.put("loggerContext", loggerContext);
        store.put("rootLogger", rootLogger);
        store.put("snapshots", snapshots);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        List<AppenderFilterSnapshot> snapshots = (List<AppenderFilterSnapshot>) store.remove("snapshots");
        if (snapshots == null) {
            return;
        }

        for (AppenderFilterSnapshot snapshot : snapshots) {
            snapshot.appender().clearAllFilters();
            for (Filter<ILoggingEvent> original : snapshot.originalFilters()) {
                snapshot.appender().addFilter(original);
            }
            snapshot.installedFilter().stop();
        }
    }

    private static Set<String> collectLoggerPrefixes(ExtensionContext context) {
        Set<String> result = new LinkedHashSet<>();

        Optional<AnnotatedElement> element = context.getElement();
        element.ifPresent(e -> addFromAnnotation(result, e.getAnnotation(SilenceLoggers.class)));

        Class<?> clazz = context.getRequiredTestClass();
        while (clazz != null) {
            addFromAnnotation(result, clazz.getAnnotation(SilenceLoggers.class));
            clazz = clazz.getEnclosingClass();
        }

        return result;
    }

    private static void addFromAnnotation(Set<String> acc, SilenceLoggers annotation) {
        if (annotation == null) {
            return;
        }
        for (String loggerName : annotation.value()) {
            if (loggerName != null) {
                String trimmed = loggerName.trim();
                if (!trimmed.isEmpty()) {
                    acc.add(trimmed);
                }
            }
        }
    }

    private record AppenderFilterSnapshot(
            Appender<ILoggingEvent> appender,
            List<Filter<ILoggingEvent>> originalFilters,
            Filter<ILoggingEvent> installedFilter
    ) {
    }

    private static final class DenyLoggerFilter extends Filter<ILoggingEvent> {
        private final Set<String> prefixes;

        private DenyLoggerFilter(Set<String> prefixes) {
            this.prefixes = Set.copyOf(prefixes);
        }

        @Override
        public FilterReply decide(ILoggingEvent event) {
            String name = event != null ? event.getLoggerName() : null;
            if (name == null) {
                return FilterReply.NEUTRAL;
            }

            for (String prefix : prefixes) {
                if (name.equals(prefix) || name.startsWith(prefix + ".")) {
                    return FilterReply.DENY;
                }
            }

            return FilterReply.NEUTRAL;
        }
    }
}
