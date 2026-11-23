package com.qualcomm.robotcore.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

/**
 * Reads and writes whole text files, as FIRST's ReadWriteFile does: UTF-8, a write creates the
 * file's folder and syncs the file to storage, and readFile/writeFile log a failure (and readFile
 * returns "") where the ...OrThrow methods throw. Files under AppUtil.ROOT_FOLDER are in the
 * simulator's stand-in for the Control Hub's storage.
 */
public class ReadWriteFile {
    public static final String TAG = "ReadWriteFile";

    protected static Charset charset = Charset.forName("UTF-8");

    public ReadWriteFile() {
    }

    public static String readFileOrThrow(File file) throws IOException {
        return charset.decode(ByteBuffer.wrap(readFileBytesOrThrow(file))).toString();
    }

    public static String readFile(File file) {
        try {
            return readFileOrThrow(file);
        } catch (IOException e) {
            RobotLog.ee(TAG, e, "error reading file: %s", file.getPath());
            return "";
        }
    }

    public static byte[] readFileBytes(File file) {
        try {
            return readFileBytesOrThrow(file);
        } catch (IOException e) {
            RobotLog.ee(TAG, e, "error reading file: %s", file.getPath());
            return new byte[0];
        }
    }

    public static byte[] readFileBytesOrThrow(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, in.available()));
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    public static void writeFile(File file, String fileContents) {
        writeFile(file.getParentFile(), file.getName(), fileContents);
    }

    public static void writeFileOrThrow(File file, String fileContents) throws IOException {
        writeFileOrThrow(file.getParentFile(), file.getName(), fileContents);
    }

    public static void writeFileOrThrow(File directory, String fileName, String fileContents) throws IOException {
        AppUtil.getInstance().ensureDirectoryExists(directory);
        ByteBuffer bytes = charset.encode(fileContents);
        FileOutputStream out = new FileOutputStream(new File(directory, fileName));
        try {
            out.write(bytes.array(), 0, bytes.limit());
            out.flush();
        } finally {
            out.getFD().sync();
            out.close();
        }
    }

    public static void writeFile(File directory, String fileName, String fileContents) {
        try {
            writeFileOrThrow(directory, fileName, fileContents);
        } catch (IOException e) {
            RobotLog.ee(TAG, e, "error writing file: %s", new File(directory, fileName).getPath());
        }
    }
}
