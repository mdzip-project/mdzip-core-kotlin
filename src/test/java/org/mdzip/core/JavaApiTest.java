package org.mdzip.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** The API as a Java (or Android Java) caller sees it. */
class JavaApiTest {
    @Test
    void createReadEditFromJava() throws IOException {
        byte[] bytes = new MdzBuilder()
                .setTitle("From Java")
                .setApplication("Java app", "1.0")
                .addText("index.md", "# From Java\n\n[Next](next.md#top)\n")
                .addText("next.md", "# Next\n")
                .toByteArray();

        MdzArchive archive = MdzArchive.open(new ByteArrayInputStream(bytes));
        assertEquals("index.md", archive.entryPoint());
        assertEquals(MdzMode.DOCUMENT, archive.mode());
        assertEquals("From Java", archive.manifest().getTitle());
        assertTrue(archive.contains("next.md"));

        MdzLink link = archive.resolveLink("next.md#top", "index.md");
        assertEquals(MdzLink.Kind.INTERNAL, link.getKind());
        assertTrue(link instanceof MdzLink.Internal);
        assertEquals("next.md", ((MdzLink.Internal) link).getPath());
        assertEquals("top", link.getFragment());

        MdzEditor editor = archive.edit();
        editor.getManifest().put("x-java", JsonNull.INSTANCE);
        editor.setText("next.md", "# Next, edited\n");
        MdzArchive edited = MdzArchive.open(editor.toByteArray());
        assertEquals(JsonNull.INSTANCE, edited.manifest().getJson().get("x-java"));

        MdzValidationReport report = edited.validate();
        assertTrue(report.isValid(), report.toString());
    }

    @Test
    void errorsCarryStableCodes() {
        try {
            MdzArchive.open(new byte[] {1, 2, 3});
            fail("expected an exception");
        } catch (MdzException e) {
            assertEquals(MdzErrorCode.ZIP_INVALID, e.getCode());
            assertEquals("ERR_ZIP_INVALID", e.getCode().getCode());
        }
        assertEquals(MdzErrorCode.PATH_TRAVERSAL, MdzPaths.check("../x.md"));
        assertEquals(MdzErrorCode.ENTRYPOINT_MISSING, MdzErrorCode.fromCode("ERR_ENTRYPOINT_MISSING"));
    }
}
