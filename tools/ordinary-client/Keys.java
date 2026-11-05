import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Presses keys on the X display the ordinary client runs on (java.awt.Robot through Xvfb's XTEST), as a person at
 * the keyboard would. Usage: DISPLAY=:99 java Keys.java chat "/aiplayer match join 1a2b3c4d" | key T | hold W 2000 |
 * click | wait 500. Each argument pair is one action, in order. Test tooling only.
 */
public final class Keys {
    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(25);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "chat" -> {
                    // Open chat with T, type the line, send it.
                    tap(robot, KeyEvent.VK_T);
                    robot.delay(400);
                    type(robot, args[++i]);
                    robot.delay(150);
                    tap(robot, KeyEvent.VK_ENTER);
                }
                case "key" -> tap(robot, KeyEvent.class.getField("VK_" + args[++i]).getInt(null));
                case "hold" -> {
                    int code = KeyEvent.class.getField("VK_" + args[++i]).getInt(null);
                    robot.keyPress(code);
                    robot.delay(Integer.parseInt(args[++i]));
                    robot.keyRelease(code);
                }
                case "click" -> {
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(40);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                }
                case "move" -> robot.mouseMove(Integer.parseInt(args[++i]), Integer.parseInt(args[++i]));
                case "wait" -> robot.delay(Integer.parseInt(args[++i]));
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
    }

    private static void tap(Robot robot, int code) {
        robot.keyPress(code);
        robot.keyRelease(code);
    }

    private static void type(Robot robot, String text) {
        for (char c : text.toCharArray()) {
            boolean shift = Character.isUpperCase(c) || "_:\"?".indexOf(c) >= 0;
            int code = switch (c) {
                case ' ' -> KeyEvent.VK_SPACE;
                case '/' -> KeyEvent.VK_SLASH;
                case '-' -> KeyEvent.VK_MINUS;
                case '_' -> KeyEvent.VK_MINUS;
                case '.' -> KeyEvent.VK_PERIOD;
                case ':' -> KeyEvent.VK_SEMICOLON;
                case '~' -> KeyEvent.VK_BACK_QUOTE;
                default -> KeyEvent.getExtendedKeyCodeForChar(Character.toLowerCase(c));
            };
            if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
            tap(robot, code);
            if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
        }
    }
}
