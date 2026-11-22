package com.qualcomm.robotcore.hardware;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

/** DcMotorImplEx is a motor that supports the DcMotorEx interface. */
public class DcMotorImplEx extends DcMotorImpl implements DcMotorEx {

    DcMotorControllerEx controllerEx;   // package-private, as in FIRST's
    int targetPositionTolerance = 5;

    public DcMotorImplEx(DcMotorController controller, int portNumber) {
        this(controller, portNumber, Direction.FORWARD);
    }

    public DcMotorImplEx(DcMotorController controller, int portNumber, Direction direction) {
        super(controller, portNumber, direction);
        this.controllerEx = (DcMotorControllerEx) controller;
    }

    @Override
    public void setMotorEnable() {
        controllerEx.setMotorEnable(portNumber);
    }

    @Override
    public void setMotorDisable() {
        controllerEx.setMotorDisable(portNumber);
    }

    @Override
    public boolean isMotorEnabled() {
        return controllerEx.isMotorEnabled(portNumber);
    }

    @Override
    public synchronized void setVelocity(double angularRate) {
        controllerEx.setMotorVelocity(portNumber, adjustAngularRate(angularRate));
    }

    @Override
    public synchronized void setVelocity(double angularRate, AngleUnit unit) {
        controllerEx.setMotorVelocity(portNumber, adjustAngularRate(angularRate), unit);
    }

    @Override
    public synchronized double getVelocity() {
        return adjustAngularRate(controllerEx.getMotorVelocity(portNumber));
    }

    @Override
    public synchronized double getVelocity(AngleUnit unit) {
        return adjustAngularRate(controllerEx.getMotorVelocity(portNumber, unit));
    }

    protected double adjustAngularRate(double angularRate) {
        if (getOperationalDirection() == Direction.REVERSE) angularRate = -angularRate;
        return angularRate;
    }

    @Override
    @Deprecated
    public void setPIDCoefficients(RunMode mode, PIDCoefficients pidCoefficients) {
        controllerEx.setPIDCoefficients(portNumber, mode, pidCoefficients);
    }

    @Override
    public void setPIDFCoefficients(RunMode mode, PIDFCoefficients pidfCoefficients) throws UnsupportedOperationException {
        controllerEx.setPIDFCoefficients(portNumber, mode, pidfCoefficients);
    }

    @Override
    public void setVelocityPIDFCoefficients(double p, double i, double d, double f) {
        setPIDFCoefficients(RunMode.RUN_USING_ENCODER, new PIDFCoefficients(p, i, d, f, MotorControlAlgorithm.PIDF));
    }

    @Override
    public void setPositionPIDFCoefficients(double p) {
        setPIDFCoefficients(RunMode.RUN_TO_POSITION, new PIDFCoefficients(p, 0, 0, 0, MotorControlAlgorithm.PIDF));
    }

    @Override
    @Deprecated
    public PIDCoefficients getPIDCoefficients(RunMode mode) {
        return controllerEx.getPIDCoefficients(portNumber, mode);
    }

    @Override
    public PIDFCoefficients getPIDFCoefficients(RunMode mode) {
        return controllerEx.getPIDFCoefficients(portNumber, mode);
    }

    @Override
    public void setTargetPositionTolerance(int tolerance) {
        targetPositionTolerance = tolerance;
    }

    @Override
    public int getTargetPositionTolerance() {
        return targetPositionTolerance;
    }

    @Override
    protected void internalSetTargetPosition(int position) {
        controllerEx.setMotorTargetPosition(portNumber, position, targetPositionTolerance);
    }

    @Override
    public double getCurrent(CurrentUnit unit) {
        return controllerEx.getMotorCurrent(portNumber, unit);
    }

    @Override
    public double getCurrentAlert(CurrentUnit unit) {
        return controllerEx.getMotorCurrentAlert(portNumber, unit);
    }

    @Override
    public void setCurrentAlert(double current, CurrentUnit unit) {
        controllerEx.setMotorCurrentAlert(portNumber, current, unit);
    }

    @Override
    public boolean isOverCurrent() {
        return controllerEx.isMotorOverCurrent(portNumber);
    }
}
