package ftcsim.hal;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Telemetry as FIRST's TelemetryImpl keeps it: items and lines (an item added to an item goes on a
 * new line after it, one added to a line goes on that line), values formatted as it formats them,
 * Func values and actions run when a transmission is composed, retained items, auto-clear, a log,
 * and a transmission interval (250 ms by default) that throttles what reaches the Driver Station.
 */
public final class SimTelemetry implements Telemetry {

    /** A composed telemetry screen as the Driver Station shows it. */
    public static final class Frame {
        public final List<String> lines;
        public final List<String> log;
        public final DisplayFormat format;
        public final long nanoTime;

        Frame(List<String> lines, List<String> log, DisplayFormat format) {
            this.lines = lines;
            this.log = log;
            this.format = format;
            this.nanoTime = System.nanoTime();
        }
    }

    /** The robot's locale: the Control Hub formats numbers as en_US does. */
    private static final DecimalFormatSymbols ROBOT_SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);

    private final Object lock = new Object();
    private final LineableContainer lines = new LineableContainer();
    private final List<Runnable> actions = new LinkedList<Runnable>();
    private final LogImpl log = new LogImpl();
    private final DecimalFormat decimalFormat = new DecimalFormat("0.####", ROBOT_SYMBOLS);

    private boolean autoClear = true;
    private int msTransmissionInterval = 250;
    private String itemSeparator = " | ";
    private String captionValueSeparator = " : ";
    private DisplayFormat displayFormat = DisplayFormat.CLASSIC;
    private boolean dirty = false;
    /** Set by update() with auto-clear on: the next line or item added clears what is shown first. */
    private boolean clearOnAdd = false;

    private List<String> lastComposedLines = Collections.emptyList();
    private Frame pending = null;
    private long lastTransmitNanos = 0;

    // ---- engine side ------------------------------------------------------------------------

    /** Returns the frame to show if one is waiting and the transmission interval has elapsed. */
    public Frame takeFrameIfDue(long nowNanos) {
        synchronized (lock) {
            if (pending == null) return null;
            if (nowNanos - lastTransmitNanos < msTransmissionInterval * 1000000L) return null;
            Frame f = pending;
            pending = null;
            lastTransmitNanos = nowNanos;
            return f;
        }
    }

    /** Sends whatever is pending right away (used when an OpMode stops). */
    public Frame flush() {
        synchronized (lock) {
            Frame f = pending;
            pending = null;
            lastTransmitNanos = System.nanoTime();
            return f;
        }
    }

    /** Called after each init_loop()/loop() of an iterative OpMode: sends telemetry if there is any. */
    public static void autoUpdate(Telemetry telemetry) {
        if (telemetry instanceof SimTelemetry) {
            ((SimTelemetry) telemetry).updateIfDirty();
        } else if (telemetry != null) {
            telemetry.update();
        }
    }

    public void updateIfDirty() {
        synchronized (lock) {
            // Retained items (e.g. Func-valued ones) and actions need refreshing every loop too.
            if (dirty || !lines.isEmpty() || !actions.isEmpty()) update();
        }
    }

    // ---- Telemetry --------------------------------------------------------------------------

    /**
     * Runs the actions and composes the lines, as TelemetryImpl does when it transmits. What an
     * action or a Func throws comes out of update(), as on the robot.
     */
    @Override
    public boolean update() {
        synchronized (lock) {
            for (Runnable action : actions) {
                action.run();
            }
            lastComposedLines = compose();
            pending = new Frame(lastComposedLines, log.snapshot(), displayFormat);
            long now = System.nanoTime();
            boolean transmitted = now - lastTransmitNanos >= msTransmissionInterval * 1000000L;
            // As FIRST's TelemetryImpl: auto-clear clears when the next data is added, not now, so
            // another update() with nothing new shows the same lines again.
            clearOnAdd = autoClear;
            dirty = false;
            return transmitted;
        }
    }

    private List<String> compose() {
        List<String> out = new ArrayList<String>(lines.list.size());
        for (Lineable l : lines.list) {
            out.add(l.getComposed(true));
        }
        return out;
    }

    private void logChanged() {
        synchronized (lock) {
            pending = new Frame(lastComposedLines, log.snapshot(), displayFormat);
        }
    }

    @Override
    public Item addData(String caption, String format, Object... args) {
        return lines.addItemAfter(null, caption, new Value<Object>(format, args));
    }

    @Override
    public Item addData(String caption, Object value) {
        return lines.addItemAfter(null, caption, new Value<Object>(value));
    }

    @Override
    public <T> Item addData(String caption, Func<T> valueProducer) {
        return lines.addItemAfter(null, caption, new Value<T>(valueProducer));
    }

    @Override
    public <T> Item addData(String caption, String format, Func<T> valueProducer) {
        return lines.addItemAfter(null, caption, new Value<T>(format, valueProducer));
    }

    @Override
    public Line addLine() {
        return lines.addLineAfter(null, "");
    }

    @Override
    public Line addLine(String lineCaption) {
        return lines.addLineAfter(null, lineCaption);
    }

    /** A line or an item is about to be added: clears what update() left, if it asked for that. */
    private void onAddData() {
        if (clearOnAdd) {
            clear();
            clearOnAdd = false;
        }
        dirty = true;
    }

    @Override
    public boolean removeItem(Item item) {
        if (item instanceof ItemImpl) {
            ItemImpl i = (ItemImpl) item;
            return i.parent.remove(i);
        }
        return false;
    }

    @Override
    public boolean removeLine(Line line) {
        if (line instanceof LineImpl) {
            LineImpl l = (LineImpl) line;
            return l.parent.remove(l);
        }
        return false;
    }

    /** Removes the items that aren't retained, and the lines left without items. */
    @Override
    public void clear() {
        synchronized (lock) {
            clearOnAdd = false;
            lines.removeAllRecurse(false);
        }
    }

    @Override
    public void clearAll() {
        synchronized (lock) {
            clearOnAdd = false;
            actions.clear();
            lines.removeAllRecurse(true);
        }
    }

    /** As TelemetryImpl: the action itself is the token removeAction() takes. */
    @Override
    public Object addAction(Runnable action) {
        synchronized (lock) {
            actions.add(action);
            return action;
        }
    }

    @Override
    public boolean removeAction(Object token) {
        synchronized (lock) {
            return actions.remove(token);
        }
    }

    @Override
    public void speak(String text) {
        SimLog.log('I', "Telemetry", "speak: " + text);
    }

    @Override
    public void speak(String text, String languageCode, String countryCode) {
        speak(text);
    }

    @Override
    public boolean isAutoClear() {
        return autoClear;
    }

    @Override
    public void setAutoClear(boolean autoClear) {
        synchronized (lock) {
            this.autoClear = autoClear;
        }
    }

    @Override
    public int getMsTransmissionInterval() {
        return msTransmissionInterval;
    }

    @Override
    public void setMsTransmissionInterval(int msTransmissionInterval) {
        synchronized (lock) {
            this.msTransmissionInterval = msTransmissionInterval;
        }
    }

    @Override
    public String getItemSeparator() {
        return itemSeparator;
    }

    @Override
    public void setItemSeparator(String itemSeparator) {
        synchronized (lock) {
            this.itemSeparator = itemSeparator;
        }
    }

    @Override
    public String getCaptionValueSeparator() {
        return captionValueSeparator;
    }

    @Override
    public void setCaptionValueSeparator(String captionValueSeparator) {
        synchronized (lock) {
            this.captionValueSeparator = captionValueSeparator;
        }
    }

    @Override
    public void setDisplayFormat(DisplayFormat displayFormat) {
        synchronized (lock) {
            this.displayFormat = displayFormat;
        }
    }

    /** As TelemetryImpl: the decimal places of Double and Float values added from now on. */
    @Override
    public void setNumDecimalPlaces(int minDecimalPlaces, int maxDecimalPlaces) {
        synchronized (lock) {
            decimalFormat.setMinimumFractionDigits(minDecimalPlaces);
            decimalFormat.setMaximumFractionDigits(maxDecimalPlaces);
        }
    }

    @Override
    public Log log() {
        return log;
    }

    // ---- items and lines, as TelemetryImpl's ------------------------------------------------------

    private interface Lineable {
        String getComposed(boolean recompose);
    }

    /** What an item shows: TelemetryImpl$Value. */
    private final class Value<T> {
        final String format;
        final Object[] formatArgs;
        final Object value;
        final Func<T> valueProducer;
        String composed;

        Value(String format, Object... formatArgs) {
            this.format = format;
            this.formatArgs = formatArgs;
            this.value = null;
            this.valueProducer = null;
        }

        Value(String format, Func<T> valueProducer) {
            this.format = format;
            this.formatArgs = null;
            this.value = null;
            this.valueProducer = valueProducer;
        }

        /** A Double or Float is formatted now, with the decimal places set now ("0.####" by default). */
        Value(Object value) {
            this.format = null;
            this.formatArgs = null;
            this.valueProducer = null;
            synchronized (lock) {
                this.value = value instanceof Double || value instanceof Float ? decimalFormat.format(value) : value;
            }
        }

        Value(Func<T> valueProducer) {
            this.format = null;
            this.formatArgs = null;
            this.value = null;
            this.valueProducer = valueProducer;
        }

        boolean isProducer() {
            return valueProducer != null;
        }

        String getComposed(boolean recompose) {
            if (recompose || composed == null) composed = compose();
            return composed;
        }

        private String compose() {
            if (format != null) {
                if (formatArgs != null) return String.format(format, formatArgs);
                if (valueProducer != null) return String.format(format, valueProducer.value());
            } else {
                if (value != null) return value.toString();
                if (valueProducer != null) return valueProducer.value().toString();
            }
            return "";
        }
    }

    /** A list of lines and items (the screen) or of items (a line): TelemetryImpl$LineableContainer. */
    private final class LineableContainer {
        final ArrayList<Lineable> list = new ArrayList<Lineable>();

        /** As TelemetryImpl, a container takes at most 255 entries; more are dropped. */
        private void boundedAddToList(int index, Lineable l) {
            if (list.size() < 255) list.add(index, l);
        }

        private int indexAfter(Lineable prev) {
            return prev == null ? list.size() : list.indexOf(prev) + 1;
        }

        Line addLineAfter(Lineable prev, String lineCaption) {
            synchronized (lock) {
                onAddData();
                LineImpl line = new LineImpl(lineCaption, this);
                boundedAddToList(indexAfter(prev), line);
                return line;
            }
        }

        Item addItemAfter(Lineable prev, String caption, Value<?> value) {
            synchronized (lock) {
                onAddData();
                ItemImpl item = new ItemImpl(this, caption, value);
                boundedAddToList(indexAfter(prev), item);
                return item;
            }
        }

        boolean isEmpty() {
            synchronized (lock) {
                return list.isEmpty();
            }
        }

        boolean remove(Lineable l) {
            synchronized (lock) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) == l) {
                        list.remove(i);
                        return true;
                    }
                }
                return false;
            }
        }

        /** Removes the items {@code all} or not retained, and the lines that are then empty. */
        boolean removeAllRecurse(boolean all) {
            synchronized (lock) {
                boolean removed = false;
                int i = 0;
                while (i < list.size()) {
                    Lineable l = list.get(i);
                    if (l instanceof LineImpl) {
                        LineImpl line = (LineImpl) l;
                        line.lineables.removeAllRecurse(all);
                        if (line.lineables.isEmpty()) {
                            list.remove(i);
                            removed = true;
                        } else {
                            i++;
                        }
                    } else if (l instanceof ItemImpl) {
                        if (all || !((ItemImpl) l).isRetained()) {
                            list.remove(i);
                            removed = true;
                        } else {
                            i++;
                        }
                    } else {
                        i++;
                    }
                }
                return removed;
            }
        }
    }

    private final class LineImpl implements Line, Lineable {
        final LineableContainer parent;
        final String lineCaption;
        final LineableContainer lineables = new LineableContainer();

        LineImpl(String lineCaption, LineableContainer parent) {
            this.lineCaption = lineCaption;
            this.parent = parent;
        }

        @Override
        public String getComposed(boolean recompose) {
            StringBuilder sb = new StringBuilder();
            sb.append(lineCaption);
            boolean first = true;
            for (Lineable l : lineables.list) {
                if (!first) sb.append(itemSeparator);
                sb.append(l.getComposed(recompose));
                first = false;
            }
            return sb.toString();
        }

        @Override
        public Item addData(String caption, String format, Object... args) {
            return lineables.addItemAfter(null, caption, new Value<Object>(format, args));
        }

        @Override
        public Item addData(String caption, Object value) {
            return lineables.addItemAfter(null, caption, new Value<Object>(value));
        }

        @Override
        public <T> Item addData(String caption, Func<T> valueProducer) {
            return lineables.addItemAfter(null, caption, new Value<T>(valueProducer));
        }

        @Override
        public <T> Item addData(String caption, String format, Func<T> valueProducer) {
            return lineables.addItemAfter(null, caption, new Value<T>(format, valueProducer));
        }
    }

    private final class ItemImpl implements Item, Lineable {
        final LineableContainer parent;
        String caption;
        Value<?> value;
        Boolean retained;

        ItemImpl(LineableContainer parent, String caption, Value<?> value) {
            this.parent = parent;
            this.caption = caption;
            this.value = value;
        }

        @Override
        public String getComposed(boolean recompose) {
            synchronized (lock) {
                return String.format("%s%s%s", caption, captionValueSeparator, value.getComposed(recompose));
            }
        }

        @Override
        public String getCaption() {
            return caption;
        }

        @Override
        public Item setCaption(String caption) {
            this.caption = caption;
            return this;
        }

        @Override
        public Item setValue(String format, Object... args) {
            return internalSetValue(new Value<Object>(format, args));
        }

        @Override
        public Item setValue(Object value) {
            return internalSetValue(new Value<Object>(value));
        }

        @Override
        public <T> Item setValue(Func<T> valueProducer) {
            return internalSetValue(new Value<T>(valueProducer));
        }

        @Override
        public <T> Item setValue(String format, Func<T> valueProducer) {
            return internalSetValue(new Value<T>(format, valueProducer));
        }

        private Item internalSetValue(Value<?> v) {
            synchronized (lock) {
                this.value = v;
                return this;
            }
        }

        /** Items whose values come from a Func are retained unless told otherwise, as in the SDK. */
        @Override
        public Item setRetained(Boolean retained) {
            synchronized (lock) {
                this.retained = retained;
                return this;
            }
        }

        @Override
        public boolean isRetained() {
            synchronized (lock) {
                return retained != null ? retained : value.isProducer();
            }
        }

        // An item added to an item goes right after it, in the same container: on a line of its own
        // after a top-level item, or after it on the same line for an item of a line.

        @Override
        public Item addData(String caption, String format, Object... args) {
            return parent.addItemAfter(this, caption, new Value<Object>(format, args));
        }

        @Override
        public Item addData(String caption, Object value) {
            return parent.addItemAfter(this, caption, new Value<Object>(value));
        }

        @Override
        public <T> Item addData(String caption, Func<T> valueProducer) {
            return parent.addItemAfter(this, caption, new Value<T>(valueProducer));
        }

        @Override
        public <T> Item addData(String caption, String format, Func<T> valueProducer) {
            return parent.addItemAfter(this, caption, new Value<T>(format, valueProducer));
        }
    }

    /** TelemetryImpl$LogImpl: 9 entries, oldest first. */
    private final class LogImpl implements Log {
        private final List<String> entries = new ArrayList<String>();
        private int capacity = 9;
        private DisplayOrder displayOrder = DisplayOrder.OLDEST_FIRST;

        List<String> snapshot() {
            synchronized (lock) {
                List<String> out = new ArrayList<String>(entries);
                if (displayOrder != DisplayOrder.OLDEST_FIRST) Collections.reverse(out);
                return out;
            }
        }

        private void prune() {
            synchronized (lock) {
                while (entries.size() > capacity && entries.size() > 0) entries.remove(0);
            }
        }

        @Override
        public int getCapacity() {
            return capacity;
        }

        @Override
        public void setCapacity(int capacity) {
            synchronized (lock) {
                this.capacity = capacity;
                prune();
            }
        }

        @Override
        public DisplayOrder getDisplayOrder() {
            return displayOrder;
        }

        @Override
        public void setDisplayOrder(DisplayOrder displayOrder) {
            synchronized (lock) {
                this.displayOrder = displayOrder;
            }
        }

        @Override
        public void add(String entry) {
            add("%s", entry);
        }

        @Override
        public void add(String format, Object... args) {
            synchronized (lock) {
                entries.add(String.format(format, args));
                prune();
            }
            logChanged();
        }

        @Override
        public void clear() {
            synchronized (lock) {
                entries.clear();
            }
            logChanged();
        }
    }
}
