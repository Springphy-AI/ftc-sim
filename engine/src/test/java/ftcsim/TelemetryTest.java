package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.junit.jupiter.api.Test;

import ftcsim.hal.SimTelemetry;

/** What the Driver Station shows for a sequence of Telemetry calls, as FIRST's TelemetryImpl shows it. */
class TelemetryTest {

    private final SimTelemetry telemetry = new SimTelemetry();
    private long now = 0;

    /** The lines the Driver Station shows after the next transmission. */
    private List<String> shown() {
        now += 1_000_000_000L;   // past the transmission interval
        SimTelemetry.Frame f = telemetry.takeFrameIfDue(now);
        return f == null ? null : f.lines;
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
        assertEquals(List.of(), shown());
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
