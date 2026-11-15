package ftcsim.hal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * {@code OpMode.blackboard}: a HashMap, as in the SDK, but each simulated robot gets its own
 * contents (on a real field every robot runs its own Robot Controller app). Calls from a thread
 * outside any robot use a shared fallback map.
 */
public final class ContextBlackboard extends HashMap<String, Object> {
    private static final long serialVersionUID = 1L;

    private final HashMap<String, Object> outside = new HashMap<String, Object>();

    private HashMap<String, Object> map() {
        RobotContext c = RobotContext.current();
        return c == null ? outside : c.blackboard;
    }

    @Override public int size() { return map().size(); }
    @Override public boolean isEmpty() { return map().isEmpty(); }
    @Override public Object get(Object key) { return map().get(key); }
    @Override public boolean containsKey(Object key) { return map().containsKey(key); }
    @Override public Object put(String key, Object value) { return map().put(key, value); }
    @Override public void putAll(Map<? extends String, ?> m) { map().putAll(m); }
    @Override public Object remove(Object key) { return map().remove(key); }
    @Override public void clear() { map().clear(); }
    @Override public boolean containsValue(Object value) { return map().containsValue(value); }
    @Override public Set<String> keySet() { return map().keySet(); }
    @Override public Collection<Object> values() { return map().values(); }
    @Override public Set<Map.Entry<String, Object>> entrySet() { return map().entrySet(); }
    @Override public Object getOrDefault(Object key, Object defaultValue) { return map().getOrDefault(key, defaultValue); }
    @Override public Object putIfAbsent(String key, Object value) { return map().putIfAbsent(key, value); }
    @Override public boolean remove(Object key, Object value) { return map().remove(key, value); }
    @Override public boolean replace(String key, Object oldValue, Object newValue) { return map().replace(key, oldValue, newValue); }
    @Override public Object replace(String key, Object value) { return map().replace(key, value); }
    @Override public Object computeIfAbsent(String key, Function<? super String, ?> f) { return map().computeIfAbsent(key, f); }
    @Override public Object computeIfPresent(String key, BiFunction<? super String, ? super Object, ?> f) { return map().computeIfPresent(key, f); }
    @Override public Object compute(String key, BiFunction<? super String, ? super Object, ?> f) { return map().compute(key, f); }
    @Override public Object merge(String key, Object value, BiFunction<? super Object, ? super Object, ?> f) { return map().merge(key, value, f); }
    @Override public void forEach(BiConsumer<? super String, ? super Object> action) { map().forEach(action); }
    @Override public void replaceAll(BiFunction<? super String, ? super Object, ?> f) { map().replaceAll(f); }
    @Override public Object clone() { return map().clone(); }
    @Override public boolean equals(Object o) { return o == this || map().equals(o); }
    @Override public int hashCode() { return map().hashCode(); }
    @Override public String toString() { return map().toString(); }
}
