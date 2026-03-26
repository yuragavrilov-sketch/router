package ru.tkbbank.sbprouter.extraction;

public class FieldRule {
    private String name;
    private String parent;
    private String key;
    private String path;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public boolean isNamedBlock() {
        return parent != null && key != null;
    }
}
