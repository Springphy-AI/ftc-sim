package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.hardware.configuration.typecontainers.ServoConfigurationType;

/** CRServoImplEx provides access to extended functionality on continuous rotation servos. */
public class CRServoImplEx extends CRServoImpl implements PwmControl {

    protected ServoControllerEx controllerEx;

    public CRServoImplEx(ServoControllerEx controller, int portNumber, ServoConfigurationType servoType) {
        this(controller, portNumber, Direction.FORWARD, servoType);
    }

    public CRServoImplEx(ServoControllerEx controller, int portNumber, Direction direction, ServoConfigurationType servoType) {
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
    public double getPulseWidth() {
        if (!isPwmEnabled()) return 0;
        PwmRange r = getPwmRange();
        return r.usPulseLower + controllerEx.getServoPosition(portNumber) * (r.usPulseUpper - r.usPulseLower);
    }

    @Override
    public void setPulseWidth(double usPulse) {
        PwmRange r = getPwmRange();
        controllerEx.setServoPosition(portNumber, (usPulse - r.usPulseLower) / (r.usPulseUpper - r.usPulseLower));
    }
}
