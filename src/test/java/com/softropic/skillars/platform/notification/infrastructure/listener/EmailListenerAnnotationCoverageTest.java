package com.softropic.skillars.platform.notification.infrastructure.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-92 AC29.3 — a guard against the <em>class</em> of bug, not just its one instance.
 *
 * <p>{@code BookingEmailListener.onBookingReminder} shipped with no annotation at all, so Spring
 * never dispatched to it and no booking reminder was ever delivered. Nothing could have caught that:
 * the listener classes are bare {@code @Component}s, an unannotated {@code on*} method compiles and
 * reads exactly like its twenty-two annotated siblings, and {@code BookingEmailListenerTest} invokes
 * the methods directly so its assertions are unaffected by whether Spring can reach them.
 *
 * <p>This test fails the build if any {@code public void on*(SomeEvent)} method on either email
 * listener loses its listener annotation. It is deliberately a plain unit test: it needs no Spring
 * context, so it costs nothing and runs in the {@code test} phase ahead of failsafe.
 *
 * <p>Scope, stated honestly: this proves the <em>annotation</em> is present, not that the enclosing
 * class is a bean or that a producer actually publishes the event. The end-to-end wiring for the one
 * method that was broken is covered by {@code BookingReminderEmailWiringIT}.
 */
@DisplayName("Every on*(Event) method on the email listeners must carry a listener annotation")
class EmailListenerAnnotationCoverageTest {

    private static final List<Class<?>> LISTENERS =
        List.of(BookingEmailListener.class, SessionPackEmailListener.class);

    private static Stream<Method> candidateHandlers(Class<?> listener) {
        return Stream.of(listener.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(m -> !Modifier.isStatic(m.getModifiers()))
            .filter(m -> !m.isSynthetic())
            .filter(m -> m.getName().startsWith("on"))
            .filter(m -> m.getParameterCount() == 1)
            .filter(m -> ApplicationEvent.class.isAssignableFrom(m.getParameterTypes()[0]));
    }

    @Test
    @DisplayName("no on*(Event) method is left unannotated — the AC29 failure mode")
    void everyEventHandlerIsAnnotated() {
        List<String> unannotated = LISTENERS.stream()
            .flatMap(EmailListenerAnnotationCoverageTest::candidateHandlers)
            .filter(m -> m.getAnnotation(TransactionalEventListener.class) == null
                      && m.getAnnotation(EventListener.class) == null)
            .map(m -> m.getDeclaringClass().getSimpleName() + "." + m.getName()
                    + "(" + m.getParameterTypes()[0].getSimpleName() + ")")
            .sorted()
            .toList();

        assertThat(unannotated)
            .as("""
                These methods look like Spring event handlers but carry neither \
                @TransactionalEventListener nor @EventListener, so publishEvent will never reach \
                them and the notification they compose will never be sent — silently, because the \
                producer still logs and stamps as though it had been. This is exactly how booking \
                reminders went undelivered for the whole life of the feature (skillars-deferred-92 \
                AC29). Annotate the method, or rename it so it no longer claims to be a handler.""")
            .isEmpty();
    }

    /**
     * The guard above is only load-bearing if it actually sees the methods. A refactor that renamed
     * the handlers, moved them to a superclass, or changed the event hierarchy would silently reduce
     * the candidate set to zero and the test would pass while checking nothing — the "a guard whose
     * test passes without it is not proven" failure this project has recorded three times.
     */
    @Test
    @DisplayName("the guard actually inspects the expected number of handlers")
    void guardSeesEveryHandler() {
        assertThat(candidateHandlers(BookingEmailListener.class).count())
            .as("BookingEmailListener's on*(Event) handlers")
            .isEqualTo(20);
        assertThat(candidateHandlers(SessionPackEmailListener.class).count())
            .as("SessionPackEmailListener's on*(Event) handlers")
            .isEqualTo(3);
    }
}
