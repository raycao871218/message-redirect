# 咕咕咕 · 珠颈斑鸠

The app is named **咕咕咕**. Its mascot is an abstract, deliberately wonky spotted dove (珠颈斑鸠) carrying an envelope: a warm-grey head, dusty taupe wings, rosy-beige chest and a dark side-of-neck patch with large ivory spots. Uneven tiny eyes, a crooked orange beak and a squishy lopsided body retain its ugly-cute personality. The launcher keeps the pale lavender background; adaptive and legacy launcher icons use the same mascot. The monochrome stencil includes pearl-dot cutouts for Android themed icons.

Assets:

- `apps/android/app/src/main/res/drawable-nodpi/messenger_mascot.png`
- `apps/android/app/src/main/res/drawable-nodpi/messenger_mascot_cyber.png`

`MascotView` derives the costume from the displayed delivery mode. Email uses the ordinary dove; database mode adds a navy cyber helmet, cyan visor and armored jacket, leaving the spotted neck patch visible. Setup and editors preview the draft mode; leaving an editor restores the saved active mode. Home, dialogs and About share the component. A short bounce respects disabled system animations.

The interface and dialogs retain warm crayon paper (`#FCF6E8`), dusty-blue wax buttons (`#BACCEB`), coral underlines, peach selected badges and uneven fine outlines. Paper and pigment textures are cached Android Canvas tiles; text stays crisp. Android permission dialogs are supplied by the OS.

Both PNGs were edited with the **built-in imagegen tool**, with genuine transparent backgrounds and original alpha retained; no external CLI or API key. Generated outputs were copied into the app resource directory above. No real user configuration is included.

Final prompt — ordinary dove:

> Use case: precise-object-edit / logo-brand. Edit the existing wonky messenger pigeon into a spotted dove (珠颈斑鸠), preserving its lopsided chubby body, waving stubby wing, uneven tiny dot eyes, awkward friendly expression, little orange beak and feet, cream envelope with blue V flap and coral seal, centered pose and dark wobbly outline. Warm grey head, dusty taupe/brown-grey wings, muted rosy-beige chest and small slate-blue accent. Essential species marker: a clear dark half-collar on the side of the neck with large ivory-white pearl dots, visible above the envelope even at icon size. Abstract ugly-cute wax-crayon doodle, matte subtle grain, big simple shapes. No realistic feather layers, glossy 3D, gradients or huge anime eyes. Whole character visible with generous genuinely transparent margins; no background, tile, text or watermark.

Final prompt — database costume:

> Use case: precise-object-edit / logo-brand. Edit the new spotted dove; use the former cyber mascot only as a costume reference. Dress the exact same dove in a compact navy helmet, clear pale-cyan visor and short armored biker jacket with slate-blue panels and restrained cyan/coral accents. Keep its grey face, rosy-beige belly, taupe stubby wings, orange beak and feet, uneven dot eyes, waving pose and cream envelope. Preserve the visible black neck patch with large ivory spots in a gap between helmet and jacket. Match the ordinary dove's centered framing, body scale and transparent margins. Flat matte crayon doodle with thick wobbly outlines and simple shapes; no scenery, text, watermark, weapons or excessive armor detail. Change only costume.
