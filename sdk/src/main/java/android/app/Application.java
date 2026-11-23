package android.app;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import android.content.Context;
import android.content.res.Resources;

/**
 * Stand-in for the Robot Controller app's {@link Application}. FIRST's SDK code asks it for the
 * package name, resources (the Vision module looks up its camera preview's view id this way) and
 * strings. Strings come from RobotCore's own string resources (copied unmodified from FIRST's
 * package at build time), looked up by the ids in {@code com.qualcomm.robotcore.R}.
 */
public class Application extends Context {
    private final Resources resources = new Resources();
    private Map<Integer, String> strings;

    @Override
    public String getPackageName() {
        return "com.qualcomm.ftcrobotcontroller";
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    public final String getString(int resId) {
        String s = strings().get(resId);
        return s != null ? s : String.format(Locale.US, "string resource 0x%08x", resId);
    }

    public final String getString(int resId, Object... formatArgs) {
        return String.format(Locale.getDefault(), getString(resId), formatArgs);
    }

    private synchronized Map<Integer, String> strings() {
        if (strings != null) return strings;
        strings = new HashMap<Integer, String>();
        try (InputStream in = Application.class.getResourceAsStream("/com/qualcomm/robotcore/values.xml")) {
            if (in == null) return strings;
            Map<String, String> byName = new HashMap<String, String>();
            NodeList list = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getElementsByTagName("string");
            for (int i = 0; i < list.getLength(); i++) {
                Element e = (Element) list.item(i);
                byName.put(e.getAttribute("name"), e.getTextContent());
            }
            // The app's resources are merged: the Hardware module's ids name strings RobotCore has too.
            for (String r : new String[]{"com.qualcomm.robotcore.R$string", "com.qualcomm.hardware.R$string"}) {
                for (Field f : Class.forName(r).getFields()) {
                    String text = byName.get(f.getName());
                    if (text != null) strings.put(f.getInt(null), text);
                }
            }
        } catch (Exception e) {
            // no strings: callers get the resource id instead
        }
        return strings;
    }
}
