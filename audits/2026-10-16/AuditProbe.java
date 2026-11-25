import java.util.*;
import com.boringutils.timehud.*;
import com.boringutils.timehud.blocking.*;
public class AuditProbe {
 public static void main(String[] args) {
  System.out.println("Untrusted app named Settings protected: " + EssentialAppPolicy.INSTANCE.isAlwaysAvailable("com.example.untrusted", "Settings", Collections.emptySet()));
  System.out.println("Same package with ordinary name protected: " + EssentialAppPolicy.INSTANCE.isAlwaysAvailable("com.example.untrusted", "Untrusted Demo", Collections.emptySet()));
  AppBlockRule rule = new AppBlockRule("com.android.settings", 1, Collections.emptySet(), true);
  System.out.println("Settings after essential allow plus 1-minute rule: " + AppControlDecisionEngine.INSTANCE.decide(BlockDecision.Allow.INSTANCE, rule, 60000L, AppSurface.OTHER));
  String largeGoal = "x".repeat(2 * 1024 * 1024);
  String json = GoalBackupFormat.INSTANCE.serialize(new GoalBackup(0L, largeGoal, ""));
  GoalBackupParseResult result = GoalBackupFormat.INSTANCE.parse(json);
  System.out.println("2 MiB goal accepted: " + (result instanceof GoalBackupParseResult.Success));
 }
}
