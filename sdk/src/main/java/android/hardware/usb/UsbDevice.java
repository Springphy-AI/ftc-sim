package android.hardware.usb;

/** Stand-in for Android's UsbDevice: a USB device's vendor and product ids. */
public class UsbDevice {
    private final String deviceName;
    private final int vendorId;
    private final int productId;

    public UsbDevice(String deviceName, int vendorId, int productId) {
        this.deviceName = deviceName;
        this.vendorId = vendorId;
        this.productId = productId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getProductId() {
        return productId;
    }
}
