package ftcsim.hal;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Telemetry as FIRST's TelemetryImpl keeps it: items and lines (an item added to an item goes on a
 * new line after it, one added to a line goes on that line), values formatted as it formats them,
 * retained items, auto-clear and a log. As TelemetryImpl.tryUpdate, update() transmits only once the
 * transmission interval (250 ms by default) has passed since the last transmission, and only then
 * runs the actions and evaluates the Func values; an update() that comes too soon is sent by the
 * event loop's tryUpdateIfDirty() once the interval has passed, unless new data comes first.
 *
 * <p>A transmission is handed to the engine through {@link #takeFrame()}, which never waits for the
 * OpMode: the physics thread takes frames, and robot code's actions and Funcs can be slow.
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

    private enum UpdateReason {USER, LOG, IFDIRTY}

    private final Object lock = new Object();
    private final LogImpl log = new LogImpl();
    private final DecimalFormat decimalFormat = new DecimalFormat("0.####", ROBOT_SYMBOLS);
    /** System.nanoTime, or a test's clock. */
    private final LongSupplier clock;
    /** The latest transmission, until the engine takes it. */
    private final AtomicReference<Frame> outbox = new AtomicReference<Frame>();

    // What TelemetryImpl.resetTelemetryForOpMode sets (all but the decimal places).
    private LineableContainer lines;
    private List<String> composedLines;
    private List<Runnable> actions;
    private long transmissionTimerStart;
    /** An update() asked for a transmission that hasn't happened yet. */
    private boolean dirty;
    /** Set by update() with auto-clear on: the next line or item added clears what is shown first. */
    private boolean clearOnAdd;
    private boolean autoClear;
    private int msTransmissionInterval;
    private String itemSeparator;
    private String captionValueSeparator;
    private DisplayFormat displayFormat = DisplayFormat.CLASSIC;

    public SimTelemetry() {
        this(new LongSupplier() {
            @Override
            public long getAsLong() {
                return System.nanoTime();
            }
        });
    }

    /** With a clock of its own (nanoseconds), for tests. */
    public SimTelemetry(LongSupplier clock) {
        this.clock = clock;
        resetForOpMode();
    }

    // ---- engine side ------------------------------------------------------------------------

    /** The latest transmission not yet shown, or null. Never waits for the OpMode. */
    public Frame takeFrame() {
        return outbox.getAndSet(null);
    }

    /**
     * TelemetryImpl.resetTelemetryForOpMode, which INIT calls: nothing shown, no actions, an empty
     * log, the transmission timer restarted, and the default settings (but not the decimal places).
     */
    public void resetForOpMode() {
        synchronized (lock) {
            lines = new LineableContainer();
            composedLines = new ArrayList<String>();
            actions = new LinkedList<Runnable>();
            log.reset();
            transmissionTimerStart = clock.getAsLong();
            dirty = false;
            clearOnAdd = false;
            autoClear = true;
            msTransmissionInterval = 250;
            captionValueSeparator = " : ";
            itemSeparator = " | ";
        }
    }

    /**
     * TelemetryImpl.tryUpdateIfDirty, which the event loop calls for a LinearOpMode about every 5 ms
     * and the OpMode's thread calls as it ends: sends what an update() or the log asked for once the
     * transmission interval allows.
     */
    public boolean tryUpdateIfDirty() {
        return tryUpdate(UpdateReason.IFDIRTY);
    }

    // ---- Telemetry --------------------------------------------------------------------------

    /**
     * Transmits if the transmission interval has passed since the last transmission: runs the
     * actions, composes the lines (evaluating the Funcs) and sends them. What an action or a Func
     * throws comes out of update(), as on the robot. Returns whether it transmitted.
     */
    @Override
    public boolean update() {
        return tryUpdate(UpdateReason.USER);
    }

    /** TelemetryImpl.tryUpdate. */
    private boolean tryUpdate(UpdateReason reason) {
        synchronized (lock) {
            boolean result = false;
            boolean intervalElapsed = (clock.getAsLong() - transmissionTimerStart) / 1e6 > msTransmissionInterval;
            boolean wantToTransmit = reason == UpdateReason.USER || reason == UpdateReason.LOG
                    || (reason == UpdateReason.IFDIRTY && (dirty || log.dirty));
            boolean recompose = reason == UpdateReason.USER || dirty;
            if (intervalElapsed && wantToTransmit) {
                for (Runnable action : actions) {
                    action.run();
                }
                if (recompose) composedLines = compose();
                // (a message with nothing in it isn't sent: the Driver Station keeps what it shows)
                if (!composedLines.isEmpty() || !log.entries.isEmpty()) {
                    outbox.set(new Frame(new ArrayList<String>(composedLines), log.snapshot(), displayFormat));
                }
                log.dirty = false;
                dirty = false;
                transmissionTimerStart = clock.getAsLong();
                result = true;
            } else if (reason == UpdateReason.USER) {
                dirty = true;
            }
            // As TelemetryImpl: auto-clear clears when the next data is added, not now, so another
            // update() with nothing new shows the same lines again.
            if (reason == UpdateReason.USER) clearOnAdd = autoClear;
            return result;
        }
    }

    private List<String> compose() {
        List<String> out = new ArrayList<String>(lines.list.size());
        for (Lineable l : lines.list) {
            out.add(l.getComposed(true));
        }
        return out;
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

    /**
     * A line or an item is about to be added: clears what update() left, if it asked for that. As
     * TelemetryImpl.onAddData, an update() still waiting to be sent isn't any more: new data is coming.
     */
    private void onAddData() {
        if (clearOnAdd) {
            clear();
            clearOnAdd = false;
        }
        dirty = false;
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
            dirty = false;
            lines.removeAllRecurse(false);
        }
    }

    @Override
    public void clearAll() {
        synchronized (lock) {
            clearOnAdd = false;
            dirty = false;
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

    /** TelemetryImpl$LogImpl: 9 entries, oldest first; an entry is sent as soon as the interval allows. */
    private final class LogImpl implements Log {
        List<String> entries;
        private int capacity;
        private DisplayOrder displayOrder;
        boolean dirty;

        void reset() {
            entries = new ArrayList<String>();
            capacity = 9;
            dirty = false;
            displayOrder = DisplayOrder.OLDEST_FIRST;
        }

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
                dirty = true;
                prune();
                tryUpdate(UpdateReason.LOG);
            }
        }

        @Override
        public void clear() {
            synchronized (lock) {
                entries.clear();
                dirty = true;
            }
        }
    }
}
