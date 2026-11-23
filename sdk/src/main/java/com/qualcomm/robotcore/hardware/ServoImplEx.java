package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.hardware.configuration.typecontainers.ServoConfigurationType;

/** ServoImplEx provides access to extended functionality on servos (PWM range and enable). */
public class ServoImplEx extends ServoImpl implements PwmControl {

    protected ServoControllerEx controllerEx;

    public ServoImplEx(ServoControllerEx controller, int portNumber, ServoConfigurationType servoType) {
        this(controller, portNumber, Direction.FORWARD, servoType);
    }

    public ServoImplEx(ServoControllerEx controller, int portNumber, Direction direction, ServoConfigurationType servoType) {
        super(controller, portNumber, direction);
        this.controllerEx = controller;
    }

    @Override
    public void setPwmRange(PwmRange range) {
        controllerEx.setServoPwmRange(portNumber, range);
    }

    @Override
    public PwmRange getPwmRange() {
        return controllerEx.getServoPwmRange(portNumber);
    }

    @Override
    public void setPwmEnable() {
        controllerEx.setServoPwmEnable(portNumber);
    }

    @Override
    public void setPwmDisable() {
        controllerEx.setServoPwmDisable(portNumber);
    }

    @Override
    public boolean isPwmEnabled() {
        return controllerEx.isServoPwmEnabled(portNumber);
    }

    @Override
    public void setPulseWidth(double usPulse) {
        controllerEx.setPulseWidth(getPortNumber(), usPulse);
    }

    @Override
    public double getPulseWidth() {
        return controllerEx.getPulseWidth(getPortNumber());
    }
}
