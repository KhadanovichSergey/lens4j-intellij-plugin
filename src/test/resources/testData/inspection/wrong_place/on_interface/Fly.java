package inspection.wrong_place.on_interface;

import dev.khbd.lens4j.core.annotations.GenLenses;
import dev.khbd.lens4j.core.annotations.Lens;

<error descr="@GenLenses is not allowed on interfaces">@GenLenses(lenses = @Lens(path = "name"))</error>
public interface Fly {
    String getName();
}
