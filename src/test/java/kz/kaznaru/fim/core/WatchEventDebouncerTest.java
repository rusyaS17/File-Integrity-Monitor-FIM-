package kz.kaznaru.fim.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WatchEventDebouncerTest {

    @Test
    void coalescesRapidModificationsAndAllowsLaterChanges() throws Exception {
        Path file = Path.of("monitored", "sample.txt").toAbsolutePath().normalize();
        List<FileWatchEvent> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch firstDelivered = new CountDownLatch(1);
        CountDownLatch laterDelivered = new CountDownLatch(1);

        try (WatchEventDebouncer debouncer = new WatchEventDebouncer(Duration.ofMillis(80))) {
            debouncer.submit(new FileWatchEvent(file, FileWatchEvent.Kind.MODIFY, "first"),
                    event -> {
                        delivered.add(event);
                        firstDelivered.countDown();
                    });
            debouncer.submit(new FileWatchEvent(file, FileWatchEvent.Kind.MODIFY, "second"),
                    event -> {
                        delivered.add(event);
                        firstDelivered.countDown();
                    });
            debouncer.submit(new FileWatchEvent(file, FileWatchEvent.Kind.MODIFY, "latest"),
                    event -> {
                        delivered.add(event);
                        firstDelivered.countDown();
                    });

            assertTrue(firstDelivered.await(2, TimeUnit.SECONDS));
            assertEquals(1, delivered.size());
            assertEquals("latest", delivered.get(0).details());

            debouncer.submit(new FileWatchEvent(file, FileWatchEvent.Kind.MODIFY, "later"),
                    event -> {
                        delivered.add(event);
                        laterDelivered.countDown();
                    });
            assertTrue(laterDelivered.await(2, TimeUnit.SECONDS));
            assertEquals(2, delivered.size());
            assertEquals("later", delivered.get(1).details());
        }
    }

    @Test
    void nonModificationEventsAreDeliveredImmediatelyAndCancelPendingModify() {
        Path file = Path.of("monitored", "sample.txt").toAbsolutePath().normalize();
        List<FileWatchEvent.Kind> delivered = new CopyOnWriteArrayList<>();

        try (WatchEventDebouncer debouncer = new WatchEventDebouncer(Duration.ofSeconds(1))) {
            debouncer.submit(new FileWatchEvent(file, FileWatchEvent.Kind.MODIFY, null),
                    event -> delivered.add(event.kind()));
            debouncer.submit(new FileWatchEvent(file, FileWatchEvent.Kind.DELETE, null),
                    event -> delivered.add(event.kind()));

            assertEquals(List.of(FileWatchEvent.Kind.DELETE), delivered);
        }
    }
}
