package io.quarkus.deployment.console;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.dev.ExceptionNotificationBuildItem;
import io.quarkus.deployment.dev.testing.MessageFormat;
import io.quarkus.deployment.dev.testing.TestConfig;
import io.quarkus.deployment.dev.testing.TestConsoleHandler;
import io.quarkus.deployment.dev.testing.TestListenerBuildItem;
import io.quarkus.deployment.dev.testing.TestSetupBuildItem;
import io.quarkus.deployment.dev.testing.TestSupport;
import io.quarkus.deployment.ide.EffectiveIdeBuildItem;
import io.quarkus.deployment.ide.Ide;
import io.quarkus.dev.console.QuarkusConsole;
import io.quarkus.runtime.console.ConsoleRuntimeConfig;

public class ConsoleProcessor {

    private static final Logger log = Logger.getLogger(ConsoleProcessor.class);

    private static boolean consoleInstalled = false;
    static volatile ConsoleStateManager.ConsoleContext context;

    /**
     * Installs the interactive console for continuous testing (and other usages)
     * <p>
     * This is only installed for dev and test mode, and runs in the build process rather than
     * a recorder to install this as early as possible
     */
    @BuildStep(onlyIf = IsDevelopment.class)
    @Produce(TestSetupBuildItem.class)
    ConsoleInstalledBuildItem setupConsole(TestConfig config,
            BuildProducer<TestListenerBuildItem> testListenerBuildItemBuildProducer,
            LaunchModeBuildItem launchModeBuildItem, ConsoleConfig consoleConfig) {

        if (consoleInstalled) {
            return ConsoleInstalledBuildItem.INSTANCE;
        }
        consoleInstalled = true;
        if (config.console.orElse(consoleConfig.enabled)) {
            //this is a bit of a hack, but we can't just inject this normally
            //this is a runtime property value, but also a build time property value
            //as when running in dev mode they are both basically equivalent
            ConsoleRuntimeConfig consoleRuntimeConfig = new ConsoleRuntimeConfig();
            consoleRuntimeConfig.color = ConfigProvider.getConfig().getOptionalValue("quarkus.console.color", Boolean.class);
            io.quarkus.runtime.logging.ConsoleConfig loggingConsoleConfig = new io.quarkus.runtime.logging.ConsoleConfig();
            loggingConsoleConfig.color = ConfigProvider.getConfig().getOptionalValue("quarkus.log.console.color",
                    Boolean.class);
            ConsoleHelper.installConsole(config, consoleConfig, consoleRuntimeConfig, loggingConsoleConfig,
                    launchModeBuildItem.isTest());
            ConsoleStateManager.init(QuarkusConsole.INSTANCE, launchModeBuildItem.getDevModeType().get());
            //note that this bit needs to be refactored so it is no longer tied to continuous testing
            if (TestSupport.instance().isEmpty() || config.continuousTesting == TestConfig.Mode.DISABLED
                    || config.flatClassPath) {
                return ConsoleInstalledBuildItem.INSTANCE;
            }
            TestConsoleHandler consoleHandler = new TestConsoleHandler(launchModeBuildItem.getDevModeType().get());
            consoleHandler.install();
            testListenerBuildItemBuildProducer.produce(new TestListenerBuildItem(consoleHandler));
        }
        return ConsoleInstalledBuildItem.INSTANCE;
    }

    @Consume(ConsoleInstalledBuildItem.class)
    @BuildStep
    void setupExceptionHandler(BuildProducer<ExceptionNotificationBuildItem> exceptionNotificationBuildItem,
            EffectiveIdeBuildItem ideSupport) {
        final AtomicReference<StackTraceElement> lastUserCode = new AtomicReference<>();
        exceptionNotificationBuildItem
                .produce(new ExceptionNotificationBuildItem(new BiConsumer<Throwable, StackTraceElement>() {
                    @Override
                    public void accept(Throwable throwable, StackTraceElement stackTraceElement) {
                        lastUserCode.set(stackTraceElement);
                    }
                }));
        if (context == null) {
            context = ConsoleStateManager.INSTANCE.createContext("Exceptions");
        }

        context.reset(
                new ConsoleCommand('x', "Opens last exception in IDE", new ConsoleCommand.HelpState(new Supplier<String>() {
                    @Override
                    public String get() {
                        return MessageFormat.RED;
                    }
                }, new Supplier<String>() {
                    @Override
                    public String get() {
                        StackTraceElement throwable = lastUserCode.get();
                        if (throwable == null) {
                            return "None";
                        }
                        return throwable.getFileName() + ":" + throwable.getLineNumber();
                    }
                }), new Runnable() {
                    @Override
                    public void run() {
                        StackTraceElement throwable = lastUserCode.get();
                        if (throwable == null) {
                            return;
                        }
                        String className = throwable.getClassName();
                        String file = throwable.getFileName();
                        if (className.contains(".")) {
                            file = className.substring(0, className.lastIndexOf('.') + 1).replace('.', File.separatorChar)
                                    + file;
                        }
                        Path fileName = Ide.findSourceFile(file);
                        if (fileName == null) {
                            log.error("Unable to find file: " + file);
                            return;
                        }
                        List<String> args = ideSupport.getIde().createFileOpeningArgs(fileName.toAbsolutePath().toString(),
                                "" + throwable.getLineNumber());
                        launchInIDE(ideSupport.getIde(), args);
                    }
                }));
    }

    protected void launchInIDE(Ide ide, List<String> args) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String effectiveCommand = ide.getEffectiveCommand();
                    if (effectiveCommand == null || effectiveCommand.isEmpty()) {
                        log.debug("Unable to determine proper launch command for IDE: " + ide);
                        return;
                    }
                    List<String> command = new ArrayList<>();
                    command.add(effectiveCommand);
                    command.addAll(args);
                    new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(10,
                                    TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("Failed to open IDE", e);
                }
            }
        }, "Launch in IDE Action").start();
    }

}
