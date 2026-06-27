package com.jx.tracker.domain.vo;

public class DailyWorkflowDependencyVo {

    private String moduleCode;

    private String moduleName;

    private String requiredCapability;

    private String mergeRisk;

    public DailyWorkflowDependencyVo() {
    }

    public DailyWorkflowDependencyVo(String moduleCode, String moduleName, String requiredCapability, String mergeRisk) {
        this.moduleCode = moduleCode;
        this.moduleName = moduleName;
        this.requiredCapability = requiredCapability;
        this.mergeRisk = mergeRisk;
    }

    public String getModuleCode() {
        return moduleCode;
    }

    public void setModuleCode(String moduleCode) {
        this.moduleCode = moduleCode;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getRequiredCapability() {
        return requiredCapability;
    }

    public void setRequiredCapability(String requiredCapability) {
        this.requiredCapability = requiredCapability;
    }

    public String getMergeRisk() {
        return mergeRisk;
    }

    public void setMergeRisk(String mergeRisk) {
        this.mergeRisk = mergeRisk;
    }
}
