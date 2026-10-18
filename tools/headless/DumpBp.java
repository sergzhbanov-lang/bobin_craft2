import com.bobincraft.warzone.*;
import java.io.*;
import java.util.*;

/** Выгрузка чертежа в текст для tools/preview/render_bp.py. Аргументы: seed kind index out.txt (kind: tower|hideout|site|checkpoint|battlefield). */
public class DumpBp {
    public static void main(String[] a) throws Exception {
        long seed = Long.parseLong(a[0]);
        String kind = a[1];
        int idx = Integer.parseInt(a[2]);
        WarzoneTerrain t = new WarzoneTerrain(seed, 1024);
        Blueprint bp = null;
        if (kind.equals("hideout")) bp = t.blueprints().hideout(t.hideoutPlan().byId(idx));
        else if (kind.equals("checkpoint")) bp = t.blueprints().checkpoint(t.poiPlan().checkpointById(idx));
        else if (kind.equals("battlefield")) bp = t.blueprints().battlefield(t.poiPlan().battlefieldById(idx));
        else {
            WarzoneBasePlan.Site s = t.basePlan().byId(idx);
            System.out.println("site " + s.id + " " + s.type + " rot=" + s.rotation + " var=" + s.variant + " " + s.faction + " " + s.region + " base=" + s.baseY);
            bp = t.blueprints().site(s);
        }
        String msg;
        while ((msg = t.blueprints().pollMessage()) != null) System.out.println(msg);
        try (PrintWriter w = new PrintWriter(new FileWriter(a[3]))) {
            bp.forEachCell((x, y, z, s, m) -> { if (!s.isAir()) w.println(x + " " + y + " " + z + " " + s); });
        }
        System.out.println(bp.name + " solid=" + bp.solidCount());
    }
}
