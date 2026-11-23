package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.junit.jupiter.api.Test;

import ftcsim.hal.SimTelemetry;

/** What the Driver Station shows for a sequence of Telemetry calls, as FIRST's TelemetryImpl shows it. */
class TelemetryTest {

    private long now = 0;
    /** A telemetry whose every update() comes a second after the last: each one transmits. */
    private final SimTelemetry telemetry = new SimTelemetry(() -> now += 1_000_000_000L);

    /** The lines of the last transmission, or null if none since the last call. */
    private List<String> shown() {
        SimTelemetry.Frame f = telemetry.takeFrame();
        return f == null ? null : f.lines;
    }

    /** The log of the last transmission, or null if none since the last call. */
    private List<String> shownLog() {
        SimTelemetry.Frame f = telemetry.takeFrame();
        return f == null ? null : f.log;
    }

    private long clock = 0;
    /** A telemetry on a clock that only moves when a test moves it. */
    private final SimTelemetry timed = new SimTelemetry(() -> clock);

    private static final long MS = 1_000_000L;

    @Test
    void funcsAndActionsRunOnlyWhenATransmissionIsDue() {
        // TelemetryImpl.tryUpdate runs the actions and composes the lines only when it transmits, at
        // most once per transmission interval, however often update() is called.
        int[] funcCalls = {0}, actionCalls = {0};
        timed.addData("count", () -> ++funcCalls[0]);
        timed.addAction(() -> actionCalls[0]++);
        clock = 300 * MS;
        int transmissions = 0;
        for (int i = 0; i < 300; i++) {   // a 1 ms loop for 300 ms
            if (timed.update()) transmissions++;
            clock += MS;
        }
        assertEquals(2, transmissions, "update() reports whether it transmitted");
        assertEquals(2, funcCalls[0], "the Func ran once per transmission");
        assertEquals(2, actionCalls[0]);
        assertEquals(List.of("count : 2"), timed.takeFrame().lines);
    }

    @Test
    void anUpdateTooSoonIsSentByTheEventLoopUnlessNewDataComesFirst() {
        timed.addData("x", 1);
        assertFalse(timed.update(), "within 250 ms of INIT");
        assertNull(timed.takeFrame());
        clock = 260 * MS;
        assertTrue(timed.tryUpdateIfDirty(), "the event loop sends what update() asked for");
        assertEquals(List.of("x : 1"), timed.takeFrame().lines);

        timed.addData("x", 2);
        assertFalse(timed.update());
        timed.addData("x", 3);   // new data: the update() waiting is not sent on its own
        clock += 300 * MS;
        assertFalse(timed.tryUpdateIfDirty());
        assertNull(timed.takeFrame());
        assertTrue(timed.update());
        assertEquals(List.of("x : 3"), timed.takeFrame().lines);
    }

    @Test
    void aLogEntryIsSentAsSoonAsTheIntervalAllows() {
        clock = 300 * MS;
        timed.addData("x", 1);
        assertTrue(timed.update());
        timed.takeFrame();
        timed.addData("x", 2);   // not updated: the log goes with the lines last sent
        clock += 300 * MS;
        timed.log().add("hello");
        SimTelemetry.Frame f = timed.takeFrame();
        assertEquals(List.of("x : 1"), f.lines);
        assertEquals(List.of("hello"), f.log);
    }

    @Test
    void theFieldDoesNotWaitForSlowTelemetryCode() throws Exception {
        // The physics thread takes telemetry frames; it must not wait while an OpMode's telemetry
        // action or Func runs (FIRST's IMU samples read the IMU in one).
        SimTelemetry t = new SimTelemetry();
        t.setMsTransmissionInterval(0);
        t.addData("x", 1);
        t.update();
        CountDownLatch inAction = new CountDownLatch(1);
        t.addAction(() -> {
            inAction.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread opMode = new Thread(t::update);
        opMode.start();
        inAction.await();
        long start = System.nanoTime();
        t.takeFrame();
        double ms = (System.nanoTime() - start) / 1e6;
        opMode.join();
        assertTrue(ms < 100, "taking a frame waited " + ms + " ms for the OpMode's action");
    }

    @Test
    void numbersAreFormattedAsTheDriverStationShowsThem() {
        // TelemetryImpl$Value formats a Double or Float with DecimalFormat("0.####") when it is added;
        // other objects with toString(), a format with String.format, and a Func's value with
        // toString() only.
        telemetry.addData("sum", 0.1 + 0.2);
        telemetry.addData("third", 1 / 3f);
        telemetry.addData("big", 1e10);
        telemetry.addData("count", 42);
        telemetry.addData("formatted", "%03d", 7);
        telemetry.addData("func", () -> 0.1 + 0.2);
        telemetry.addData("nothing", (Object) null);
        telemetry.update();
        assertEquals(List.of("sum : 0.3", "third : 0.3333", "big : 10000000000", "count : 42", "formatted : 007",
                "func : 0.30000000000000004", "nothing : "), shown());
    }

    @Test
    void setNumDecimalPlacesChangesTheValuesAddedAfterIt() {
        telemetry.setNumDecimalPlaces(2, 3);
        telemetry.addData("pi", Math.PI);
        telemetry.addData("one", 1.0);
        telemetry.addData("func", () -> Math.PI);   // not a number TelemetryImpl formats
        telemetry.update();
        assertEquals(List.of("pi : 3.142", "one : 1.00", "func : 3.141592653589793"), shown());
    }

    @Test
    void dataAddedToAnItemGoesOnANewLineRightAfterIt() {
        // ItemImpl.addData adds to the item's container after the item: a new line after a top-level
        // item, the same line for an item of a line.
        Telemetry.Item first = telemetry.addData("a", 1);
        telemetry.addData("c", 3);
        first.addData("b", 2);
        telemetry.addLine("pose ").addData("x", 4).addData("y", 5);
        telemetry.update();
        assertEquals(List.of("a : 1", "b : 2", "c : 3", "pose x : 4 | y : 5"), shown());
    }

    @Test
    void theLogShowsItsOldestEntryFirst() {
        assertEquals(Telemetry.Log.DisplayOrder.OLDEST_FIRST, telemetry.log().getDisplayOrder());
        telemetry.log().add("one");
        telemetry.log().add("two %d", 2);
        telemetry.log().add("50%");   // add(entry) is add("%s", entry): a percent sign is text
        telemetry.update();
        assertEquals(List.of("one", "two 2", "50%"), shownLog());
    }

    @Test
    void whatAFuncOrAnActionThrowsComesOutOfUpdate() {
        telemetry.addData("broken", () -> {
            throw new IllegalStateException("sensor gone");
        });
        assertThrows(IllegalStateException.class, telemetry::update);
        telemetry.clearAll();
        Runnable action = () -> {
            throw new ArithmeticException();
        };
        assertSame(action, telemetry.addAction(action), "the token is the action, as TelemetryImpl returns it");
        assertThrows(ArithmeticException.class, telemetry::update);
        telemetry.removeAction(action);
        telemetry.update();
    }

    @Test
    void anotherUpdateWithNothingAddedShowsTheSameLines() {
        telemetry.addData("x", 42);
        telemetry.update();
        assertEquals(List.of("x : 42"), shown());
        // A helper that calls update() again, with nothing new: the robot shows x : 42 again, not an empty screen.
        telemetry.update();
        assertEquals(List.of("x : 42"), shown());
    }

    @Test
    void theNextDataAddedAfterAnUpdateReplacesTheScreen() {
        telemetry.addData("x", 1);
        telemetry.update();
        shown();
        telemetry.addData("y", 2);
        telemetry.addLine("ready");
        telemetry.update();
        assertEquals(List.of("y : 2", "ready"), shown());
    }

    @Test
    void anItemAddedToALineAfterAnUpdateAlsoReplacesTheScreen() {
        telemetry.addData("x", 1);
        telemetry.update();
        Telemetry.Line line = telemetry.addLine("pose");
        line.addData("x", 3).addData("y", 4);
        telemetry.update();
        assertEquals(List.of("pose" + "x : 3 | y : 4"), shown());
    }

    @Test
    void retainedItemsAndExplicitClearsBehaveAsBefore() {
        telemetry.addData("kept", 1).setRetained(true);
        telemetry.addData("once", 2);
        telemetry.update();
        assertEquals(List.of("kept : 1", "once : 2"), shown());
        telemetry.addData("next", 3);   // clears what isn't retained
        telemetry.update();
        assertEquals(List.of("kept : 1", "next : 3"), shown());
        telemetry.clearAll();
        telemetry.update();
        // A TelemetryMessage with no data isn't sent: the Driver Station keeps showing the last one.
        assertNull(shown());
    }

    @Test
    void withoutAutoClearLinesAccumulate() {
        telemetry.setAutoClear(false);
        telemetry.addData("a", 1);
        telemetry.update();
        telemetry.addData("b", 2);
        telemetry.update();
        assertEquals(List.of("a : 1", "b : 2"), shown());
    }
}
