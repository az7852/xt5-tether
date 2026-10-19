package io.github.xt5tether;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class GallerySelectionTest {
    @Test public void toggleEntersSelectionAndExitClears() {
        GallerySelection s=new GallerySelection();s.available(Arrays.asList("a","b"));
        s.toggle("a");assertTrue(s.active());assertTrue(s.contains("a"));
        s.toggle("a");assertEquals(0,s.size());assertTrue(s.active());
        s.toggle("b");s.exit();assertFalse(s.active());assertEquals(0,s.size());
    }
    @Test public void pendingOrUnknownNamesCannotBeSelected() {
        GallerySelection s=new GallerySelection();s.available(Collections.singleton("saved"));
        s.toggle("pending:1");assertFalse(s.active());assertEquals(0,s.size());
        s.enter();s.selectAll(true);assertEquals(Collections.singleton("saved"),s.snapshot());
    }
    @Test public void newCapturesDoNotJoinAnExistingSelection() {
        GallerySelection s=new GallerySelection();s.available(Arrays.asList("a","b"));s.enter();s.selectAll(true);
        s.available(Arrays.asList("new","a","b"));assertEquals(2,s.size());assertFalse(s.allSelected());
        s.selectAll(true);assertEquals(3,s.size());assertTrue(s.allSelected());s.selectAll(false);assertEquals(0,s.size());
    }
    @Test public void removedPhotosArePrunedAndExportDoesNotChangeIdentity() {
        GallerySelection s=new GallerySelection();s.available(Arrays.asList("received_a.jpg","b"));s.toggle("received_a.jpg");s.toggle("b");
        s.available(Collections.singleton("received_a.jpg"));assertEquals(Collections.singleton("received_a.jpg"),s.snapshot());
        assertTrue(s.allSelected());
    }
    @Test public void confirmationSnapshotDoesNotChangeWithSelection() {
        GallerySelection s=new GallerySelection();s.available(Arrays.asList("a","b"));s.toggle("a");Set<String> snapshot=s.snapshot();
        s.toggle("b");s.exit();assertEquals(Collections.singleton("a"),snapshot);
        try {snapshot.add("other");fail("Mutable deletion snapshot");}catch(UnsupportedOperationException expected) { }
    }
    @Test public void emptyLibraryHasNoAllSelectedState() {
        GallerySelection s=new GallerySelection();s.enter();s.selectAll(true);assertFalse(s.allSelected());assertEquals(0,s.availableCount());
    }
}
