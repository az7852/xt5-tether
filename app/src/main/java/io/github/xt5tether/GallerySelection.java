package io.github.xt5tether;

import java.util.*;

/** Logical received filenames stay stable when a private file is exported to MediaStore. */
final class GallerySelection {
    private final Set<String> available=new LinkedHashSet<>(), selected=new LinkedHashSet<>();
    private boolean active;
    void available(Collection<String> names) {available.clear();available.addAll(names);selected.retainAll(available);}
    void enter() {active=true;}
    void exit() {active=false;selected.clear();}
    boolean active() {return active;}
    void toggle(String name) {
        if(!available.contains(name))return;
        active=true;if(!selected.remove(name))selected.add(name);
    }
    void selectAll(boolean value) {selected.clear();if(value)selected.addAll(available);}
    boolean allSelected() {return !available.isEmpty() && selected.size()==available.size();}
    boolean contains(String name) {return selected.contains(name);}
    int size() {return selected.size();}
    int availableCount() {return available.size();}
    Set<String> snapshot() {return Collections.unmodifiableSet(new LinkedHashSet<>(selected));}
}
