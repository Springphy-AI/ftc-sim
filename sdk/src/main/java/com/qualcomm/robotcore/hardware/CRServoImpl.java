package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.util.Range;

/** ContinuousRotationServoImpl provides an implementation of continuous rotation servo functionality. */
public class CRServoImpl implements CRServo {

    protected static final double apiPowerMin = -1.0;
    protected static final double apiPowerMax = 1.0;
    protected static final double apiServoPositionMin = 0.0;
    protected static final double apiServoPositionMax = 1.0;

    protected ServoController controller;
    protected int portNumber;
    protected Direction direction = Direction.FORWARD;

    public CRServoImpl(ServoController controller, int portNumber) {
        this(controller, portNumber, Direction.FORWARD);
    }

    public CRServoImpl(ServoController controller, int portNumber, Direction direction) {
        this.direction = direction;
        this.controller = controller;
        this.portNumber = portNumber;
    }

    @Override
    public Manufacturer getManufacturer() {
        return controller.getManufacturer();
    }

    @Override
    public String getDeviceName() {
        return "Continuous Rotation Servo";
    }

    @Override
    public String getConnectionInfo() {
        return controller.getConnectionInfo() + "; port " + portNumber;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public synchronized void resetDeviceConfigurationForOpMode() {
        this.direction = Direction.FORWARD;
    }

    @Override
    public void close() {
    }

    @Override
    public ServoController getController() {
        return controller;
    }

    @Override
    public int getPortNumber() {
        return portNumber;
    }

    @Override
    public synchronized void setDirection(Direction direction) {
        this.direction = direction;
    }

    @Override
    public synchronized Direction getDirection() {
        return direction;
    }

    @Override
    public void setPower(double power) {
        if (direction == Direction.REVERSE) power = -power;
        power = Range.clip(power, apiPowerMin, apiPowerMax);
        double position = Range.scale(power, apiPowerMin, apiPowerMax, apiServoPositionMin, apiServoPositionMax);
        controller.setServoPosition(portNumber, position);
    }

    @Override
    public double getPower() {
        double position = controller.getServoPosition(portNumber);
        double power = Range.scale(position, apiServoPositionMin, apiServoPositionMax, apiPowerMin, apiPowerMax);
        if (direction == Direction.REVERSE) power = -power;
        return power;
    }
}
