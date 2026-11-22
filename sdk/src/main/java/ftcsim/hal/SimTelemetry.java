package ftcsim.hal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Telemetry implementation with the SDK's semantics: items and lines, Func values evaluated at
 * transmission, retained items, auto-clear, a log, and a transmission interval (250 ms by default)
 * that throttles what reaches the Driver Station.
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

    private final Object lock = new Object();
    private final List<LineImpl> lines = new ArrayList<LineImpl>();
    private final Map<Object, Runnable> actions = new LinkedHashMap<Object, Runnable>();
    private final LogImpl log = new LogImpl();

    private boolean autoClear = true;
    private int msTransmissionInterval = 250;
    private String itemSeparator = " | ";
    private String captionValueSeparator = " : ";
    private DisplayFormat displayFormat = DisplayFormat.CLASSIC;
    private int minDecimals = -1;
    private int maxDecimals = -1;
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

    @Override
    public boolean update() {
        synchronized (lock) {
            for (Runnable action : new ArrayList<Runnable>(actions.values())) {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    SimLog.log('W', "Telemetry", "telemetry action threw " + e);
                }
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
        List<String> out = new ArrayList<String>(lines.size());
        for (LineImpl line : lines) {
            out.add(line.render());
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
        synchronized (lock) {
            return newLine("").addItem(caption).setValue(format, args);
        }
    }

    @Override
    public Item addData(String caption, Object value) {
        synchronized (lock) {
            return newLine("").addItem(caption).setValue(value);
        }
    }

    @Override
    public <T> Item addData(String caption, Func<T> valueProducer) {
        synchronized (lock) {
            return newLine("").addItem(caption).setValue(valueProducer);
        }
    }

    @Override
    public <T> Item addData(String caption, String format, Func<T> valueProducer) {
        synchronized (lock) {
            return newLine("").addItem(caption).setValue(format, valueProducer);
        }
    }

    @Override
    public Line addLine() {
        return addLine("");
    }

    @Override
    public Line addLine(String lineCaption) {
        synchronized (lock) {
            return newLine(lineCaption);
        }
    }

    /** A line or an item is about to be added: clears what update() left, if it asked for that. */
    private void onAddData() {
        if (clearOnAdd) {
            clear();
        }
    }

    private LineImpl newLine(String caption) {
        onAddData();
        LineImpl line = new LineImpl(caption == null ? "null" : caption);
        lines.add(line);
        dirty = true;
        return line;
    }

    @Override
    public boolean removeItem(Item item) {
        synchronized (lock) {
            for (Iterator<LineImpl> it = lines.iterator(); it.hasNext(); ) {
                LineImpl line = it.next();
                if (line.items.remove(item)) {
                    if (line.items.isEmpty() && line.caption.isEmpty()) it.remove();
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public boolean removeLine(Line line) {
        synchronized (lock) {
            return lines.remove(line);
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            clearOnAdd = false;
            for (Iterator<LineImpl> it = lines.iterator(); it.hasNext(); ) {
                LineImpl line = it.next();
                for (Iterator<ItemImpl> items = line.items.iterator(); items.hasNext(); ) {
                    if (!items.next().retained) items.remove();
                }
                if (line.items.isEmpty()) it.remove();
            }
        }
    }

    @Override
    public void clearAll() {
        synchronized (lock) {
            clearOnAdd = false;
            lines.clear();
            actions.clear();
        }
    }

    @Override
    public Object addAction(Runnable action) {
        synchronized (lock) {
            Object token = new Object();
            actions.put(token, action);
            return token;
        }
    }

    @Override
    public boolean removeAction(Object token) {
        synchronized (lock) {
            return actions.remove(token) != null;
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
        synchronized (lock) {
            return autoClear;
        }
    }

    @Override
    public void setAutoClear(boolean autoClear) {
        synchronized (lock) {
            this.autoClear = autoClear;
        }
    }

    @Override
    public int getMsTransmissionInterval() {
        synchronized (lock) {
            return msTransmissionInterval;
        }
    }

    @Override
    public void setMsTransmissionInterval(int msTransmissionInterval) {
        synchronized (lock) {
            this.msTransmissionInterval = Math.max(0, msTransmissionInterval);
        }
    }

    @Override
    public String getItemSeparator() {
        synchronized (lock) {
            return itemSeparator;
        }
    }

    @Override
    public void setItemSeparator(String itemSeparator) {
        synchronized (lock) {
            this.itemSeparator = itemSeparator;
        }
    }

    @Override
    public String getCaptionValueSeparator() {
        synchronized (lock) {
            return captionValueSeparator;
        }
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

    @Override
    public void setNumDecimalPlaces(int minDecimalPlaces, int maxDecimalPlaces) {
        synchronized (lock) {
            this.minDecimals = Math.max(0, minDecimalPlaces);
            this.maxDecimals = Math.max(this.minDecimals, maxDecimalPlaces);
        }
    }

    @Override
    public Log log() {
        return log;
    }

    private String formatValue(Object value) {
        if (value == null) return "null";
        if (maxDecimals >= 0 && (value instanceof Double || value instanceof Float)) {
            double d = ((Number) value).doubleValue();
            String s = String.format(Locale.US, "%." + maxDecimals + "f", d);
            if (s.indexOf('.') >= 0) {
                int keep = s.indexOf('.') + 1 + minDecimals;
                int end = s.length();
                while (end > keep && s.charAt(end - 1) == '0') end--;
                if (end > 0 && s.charAt(end - 1) == '.') end--;
                s = s.substring(0, end);
            }
            return s;
        }
        return value.toString();
    }

    // ---- items and lines --------------------------------------------------------------------

    private final class LineImpl implements Line {
        final String caption;
        final List<ItemImpl> items = new ArrayList<ItemImpl>();

        LineImpl(String caption) {
            this.caption = caption;
        }

        ItemImpl addItem(String caption) {
            onAddData();
            ItemImpl item = new ItemImpl(this, caption);
            items.add(item);
            dirty = true;
            return item;
        }

        String render() {
            StringBuilder sb = new StringBuilder(caption);
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(itemSeparator);
                sb.append(items.get(i).render());
            }
            return sb.toString();
        }

        @Override
        public Item addData(String caption, String format, Object... args) {
            synchronized (lock) {
                return addItem(caption).setValue(format, args);
            }
        }

        @Override
        public Item addData(String caption, Object value) {
            synchronized (lock) {
                return addItem(caption).setValue(value);
            }
        }

        @Override
        public <T> Item addData(String caption, Func<T> valueProducer) {
            synchronized (lock) {
                return addItem(caption).setValue(valueProducer);
            }
        }

        @Override
        public <T> Item addData(String caption, String format, Func<T> valueProducer) {
            synchronized (lock) {
                return addItem(caption).setValue(format, valueProducer);
            }
        }
    }

    private final class ItemImpl implements Item {
        final LineImpl line;
        String caption;
        String value = "";
        Func<?> producer;
        String producerFormat;
        boolean retained = false;

        ItemImpl(LineImpl line, String caption) {
            this.line = line;
            this.caption = caption == null ? "null" : caption;
        }

        String render() {
            String v = value;
            if (producer != null) {
                try {
                    Object produced = producer.value();
                    v = producerFormat != null ? String.format(producerFormat, produced) : formatValue(produced);
                } catch (RuntimeException e) {
                    v = "<" + e.getClass().getSimpleName() + ">";
                }
            }
            return caption + captionValueSeparator + v;
        }

        @Override
        public String getCaption() {
            return caption;
        }

        @Override
        public Item setCaption(String caption) {
            synchronized (lock) {
                this.caption = caption;
                dirty = true;
                return this;
            }
        }

        @Override
        public Item setValue(String format, Object... args) {
            synchronized (lock) {
                this.value = String.format(format, args);
                this.producer = null;
                dirty = true;
                return this;
            }
        }

        @Override
        public Item setValue(Object value) {
            synchronized (lock) {
                this.value = formatValue(value);
                this.producer = null;
                dirty = true;
                return this;
            }
        }

        @Override
        public <T> Item setValue(Func<T> valueProducer) {
            synchronized (lock) {
                this.producer = valueProducer;
                this.producerFormat = null;
                // Items whose values come from a Func are retained by default, as in the SDK.
                this.retained = true;
                dirty = true;
                return this;
            }
        }

        @Override
        public <T> Item setValue(String format, Func<T> valueProducer) {
            synchronized (lock) {
                this.producer = valueProducer;
                this.producerFormat = format;
                this.retained = true;
                dirty = true;
                return this;
            }
        }

        @Override
        public Item setRetained(Boolean retained) {
            synchronized (lock) {
                this.retained = retained == null ? producer != null : retained;
                return this;
            }
        }

        @Override
        public boolean isRetained() {
            synchronized (lock) {
                return retained;
            }
        }

        @Override
        public Item addData(String caption, String format, Object... args) {
            return line.addData(caption, format, args);
        }

        @Override
        public Item addData(String caption, Object value) {
            return line.addData(caption, value);
        }

        @Override
        public <T> Item addData(String caption, Func<T> valueProducer) {
            return line.addData(caption, valueProducer);
        }

        @Override
        public <T> Item addData(String caption, String format, Func<T> valueProducer) {
            return line.addData(caption, format, valueProducer);
        }
    }

    private final class LogImpl implements Log {
        private final LinkedList<String> entries = new LinkedList<String>();
        private int capacity = 9;
        private DisplayOrder displayOrder = DisplayOrder.NEWEST_FIRST;

        List<String> snapshot() {
            synchronized (lock) {
                List<String> out = new ArrayList<String>(entries);
                if (displayOrder == DisplayOrder.NEWEST_FIRST) Collections.reverse(out);
                return out;
            }
        }

        @Override
        public int getCapacity() {
            synchronized (lock) {
                return capacity;
            }
        }

        @Override
        public void setCapacity(int capacity) {
            synchronized (lock) {
                this.capacity = Math.max(0, capacity);
                while (entries.size() > this.capacity) entries.removeFirst();
            }
        }

        @Override
        public DisplayOrder getDisplayOrder() {
            synchronized (lock) {
                return displayOrder;
            }
        }

        @Override
        public void setDisplayOrder(DisplayOrder displayOrder) {
            synchronized (lock) {
                this.displayOrder = displayOrder;
            }
        }

        @Override
        public void add(String entry) {
            synchronized (lock) {
                entries.addLast(entry);
                while (entries.size() > capacity) entries.removeFirst();
            }
            logChanged();
        }

        @Override
        public void add(String format, Object... args) {
            add(String.format(format, args));
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
