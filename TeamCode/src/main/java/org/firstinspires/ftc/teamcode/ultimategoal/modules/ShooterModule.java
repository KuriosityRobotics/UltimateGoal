package org.firstinspires.ftc.teamcode.ultimategoal.modules;

import com.acmerobotics.dashboard.config.Config;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.util.Range;

import org.firstinspires.ftc.teamcode.ultimategoal.Robot;
import org.firstinspires.ftc.teamcode.ultimategoal.util.TelemetryProvider;
import org.firstinspires.ftc.teamcode.ultimategoal.util.auto.PIDController;

import java.util.ArrayList;
import java.util.HashMap;

import static org.firstinspires.ftc.teamcode.ultimategoal.util.math.MathFunctions.angleWrap;

@Config
public class ShooterModule implements Module, TelemetryProvider {
    Robot robot;
    boolean isOn;

    public static double P = 0.65;
    public static double I = 0.35;
    public static double D = 0.0;

    // States
    private double targetTurretAngle;
    public double flyWheelTargetSpeed;
    public boolean indexRing;
    public double shooterFlapPosition = FLAP_LOWER_LIMIT;

    // Data
    private double currentTurretAngle;
    private IndexerPosition indexerPosition;

    // Motors
    private DcMotorEx flyWheel1;
    private DcMotorEx flyWheel2;

    // Encoders
    private DcMotor turretEncoder;

    // Servos
    private CRServo turretLeft;
    private CRServo turretRight;
    private Servo indexerServo;
    private Servo shooterFlap;

    // Helpers
    long indexTime;

    // Constants
    private static final double TURRET_MINIMUM_ANGLE = Math.toRadians(-180);
    private static final double TURRET_MAXIMUM_ANGLE = Math.toRadians(180);

    private static final double TURRET_ENCODER_TO_ANGLE = 4842.60745;
    private static final int FLYWHEEL_SPEED_THRESHOLD = 200;

    private static final double FLAP_STORE_POSITION = 0.0873856;
    private static final double FLAP_LOWER_LIMIT = 0;
    private static final double FLAP_UPPER_LIMIT = 0.29255;

    private static final double INDEXER_PUSHED_POSITION = 0.45;
    private static final double INDEXER_RETRACTED_POSITION = 0.72;

    private static final int INDEXER_PUSHED_TIME_MS = 250; // since start of index
    public static final int INDEXER_RETURNED_TIME_MS = 300; // since start of index

    public enum IndexerPosition {RETRACTED, PUSHED}

    PIDController turretController;

    public ShooterModule(Robot robot, boolean isOn) {
        robot.telemetryDump.registerProvider(this);
        this.robot = robot;
        this.isOn = isOn;

        turretController = new PIDController(0.65, 0.35, 0.00, robot);

        targetTurretAngle = 0;

        flyWheelTargetSpeed = 0;

        indexRing = false;
        indexerPosition = IndexerPosition.RETRACTED;
        indexTime = 0;

        currentTurretAngle = 0;
    }

    @Override
    public void initModules() {
        initFlywheels();

        turretEncoder = robot.getDcMotor("intakeBottom");

        turretEncoder.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        turretEncoder.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);

        turretLeft = robot.hardwareMap.crservo.get("leftTurret");
        turretRight = robot.hardwareMap.crservo.get("rightTurret");

        indexerServo = robot.getServo("indexer");

        shooterFlap = robot.getServo("shooterFlap");
    }

    private void initFlywheels() {
        flyWheel1 = (DcMotorEx) robot.getDcMotor("flyWheel1");
        flyWheel2 = (DcMotorEx) robot.getDcMotor("flyWheel2");

        flyWheel1.setDirection(DcMotorSimple.Direction.FORWARD);
        flyWheel2.setDirection(DcMotorSimple.Direction.FORWARD);

        flyWheel1.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        flyWheel2.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);

        flyWheel1.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        flyWheel2.setMode(DcMotor.RunMode.RUN_USING_ENCODER);

        flyWheel1.setVelocityPIDFCoefficients(9, 0.4, 0, 11.7);
        flyWheel2.setVelocityPIDFCoefficients(9, 0.4, 0, 11.7);
    }

    @Override
    public void update() {
        long currentTime = robot.getCurrentTimeMilli();

        turretLogic();

        shooterFlap.setPosition(Range.clip(shooterFlapPosition, FLAP_LOWER_LIMIT, FLAP_UPPER_LIMIT));

        setFlywheelMotors();

        indexerLogic(currentTime);
    }

    private double pow;

    private void turretLogic() {
//        Log.v("turret", "--------");

        this.currentTurretAngle = turretEncoder.getCurrentPosition() / TURRET_ENCODER_TO_ANGLE;

        double error = targetTurretAngle - currentTurretAngle;

        turretController.P = P;
        turretController.I = I;
        turretController.D = D;

        if (Math.abs(error) < Math.toDegrees(2)) {
            turretController.reset();
        }

        pow = turretController.calculatePID(error);

        turnTurret(pow);
    }

    private void turnTurret(double power) {
        if (power == 0) {
            turretLeft.setPower(0);
            turretRight.setPower(0);
        }

        power = Range.clip(power, -1, 1);

        if (power > 0) {
            turretLeft.setDirection(DcMotorSimple.Direction.REVERSE);
            turretRight.setDirection(DcMotorSimple.Direction.REVERSE);
        } else {
            turretLeft.setDirection(DcMotorSimple.Direction.FORWARD);
            turretRight.setDirection(DcMotorSimple.Direction.FORWARD);
        }

        double truePower = Math.abs(power);
        turretLeft.setPower(truePower);
        turretRight.setPower(truePower);
    }

    private void setFlywheelMotors() {
        flyWheel1.setVelocity(flyWheelTargetSpeed);
        flyWheel2.setVelocity(flyWheelTargetSpeed);

        if (flywheelsUpToSpeed() && flyWheelTargetSpeed > 0) {
            robot.setLedColor(0, 200, 200);
        } else {
            robot.setLedColor(25, 25, 25);
        }
    }

    private void indexerLogic(long currentTime) {
        if (currentTime < indexTime + INDEXER_PUSHED_TIME_MS) {
            indexerPosition = IndexerPosition.PUSHED;
        } else if (currentTime < indexTime + INDEXER_RETURNED_TIME_MS) {
            indexerPosition = IndexerPosition.RETRACTED;
        } else if (indexRing) {
            indexerPosition = IndexerPosition.PUSHED;

            indexRing = false;
            indexTime = currentTime;
        } else {
            indexerPosition = IndexerPosition.RETRACTED;
        }

        setIndexerPosition();
    }

    private void setIndexerPosition() {
        switch (indexerPosition) {
            case PUSHED:
                indexerServo.setPosition(INDEXER_PUSHED_POSITION);
                break;
            case RETRACTED:
                indexerServo.setPosition(INDEXER_RETRACTED_POSITION);
                break;
        }
    }

    public boolean flywheelsUpToSpeed() {
        return flyWheel1.getVelocity() > flyWheelTargetSpeed - FLYWHEEL_SPEED_THRESHOLD
                && flyWheel2.getVelocity() > flyWheelTargetSpeed - FLYWHEEL_SPEED_THRESHOLD;
    }

    /**
     * Request for the turret to turn to face a given angle, relative to the robot.
     *
     * @param angle
     */
    public void setTargetTurretAngle(double angle) {
        double wrapped = angleWrap(angle);

        double target = currentTurretAngle + angleWrap(wrapped - currentTurretAngle);

        while (target > TURRET_MAXIMUM_ANGLE) {
            target -= 2 * Math.PI;
        }

        while (target < TURRET_MINIMUM_ANGLE) {
            target += 2 * Math.PI;
        }

        targetTurretAngle = target;
    }

    public double getCurrentTurretAngle() {
        return currentTurretAngle;
    }

    public boolean isFinishedIndexing() {
        return robot.getCurrentTimeMilli() > indexTime + INDEXER_PUSHED_TIME_MS;
    }

    public boolean isIndexerReturned() {
        return robot.getCurrentTimeMilli() > indexTime + INDEXER_RETURNED_TIME_MS;
    }

    public IndexerPosition getIndexerPosition() {
        return indexerPosition;
    }

    @Override
    public boolean isOn() {
        return isOn;
    }

    @Override
    public HashMap<String, Object> getDashboardData() {
        HashMap<String, Object> data = new HashMap<>();
        data.put("turretPos", Math.toDegrees(currentTurretAngle));
        data.put("turretTargetPos", Math.toDegrees(angleWrap(targetTurretAngle)));
        return data;
    }

    @Override
    public ArrayList<String> getTelemetryData() {
        ArrayList<String> data = new ArrayList<>();
        data.add("Target speed: " + flyWheelTargetSpeed);
        data.add("Flywheel1 speed: " + flyWheel1.getVelocity());
        data.add("isUpToSpeed: " + flywheelsUpToSpeed());
        data.add("--");
        data.add("Target turret angle: " + Math.toDegrees(angleWrap(targetTurretAngle)));
        data.add("Current turret angle: " + Math.toDegrees(currentTurretAngle));
        data.add("pow: " + pow);
        data.add("Flap angle: " + shooterFlapPosition);
        return data;
    }

    public String getName() {
        return "ShooterModule";
    }
}
