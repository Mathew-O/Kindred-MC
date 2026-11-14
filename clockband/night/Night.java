import net.minecraft.util.math.MathHelper;
public class Night {
    static boolean night(long time, float rain, float thunder) {
        double d = MathHelper.fractionalPart((double) time / 24000.0 - 0.25);
        double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
        float angle = (float) (d * 2.0 + e) / 3.0f;
        double r = 1.0 - (double) (rain * 5.0f) / 16.0, t = 1.0 - (double) (thunder * 5.0f) / 16.0;
        double f = 0.5 + 2.0 * MathHelper.clamp((double) MathHelper.cos(angle * 6.2831855f), -0.25, 0.25);
        return (int) ((1.0 - f * r * t) * 11.0) >= 4;
    }
    public static void main(String[] a) {
        float[][] w = {{0, 0}, {1, 0}, {1, 1}};
        String[] n = {"clear", "rain", "thunder"};
        for (int k = 0; k < 3; k++) {
            StringBuilder s = new StringBuilder(n[k] + ":");
            boolean prev = night(23999, w[k][0], w[k][1]);
            int count = 0;
            for (long t = 0; t < 24000; t++) { boolean x = night(t, w[k][0], w[k][1]); if (x) count++; if (x != prev) s.append(x ? " night from " : " day from ").append(t); prev = x; }
            System.out.println(s + " (night ticks " + count + ")");
        }
    }
}
