package com.fishmatrix.chat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 分层守护：controller → service → mapper，实体不出持久化层。 */
class ArchitectureTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter().importPackages("com.fishmatrix.chat");

    @Test
    @DisplayName("controller 不得直接依赖 mapper")
    void controllerMustNotTouchMappers() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..mapper..")
                .because("controller 只做收/转/发，数据访问统一由 service 负责");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("service 不得依赖 controller")
    void serviceMustNotDependOnController() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..controller..")
                .because("依赖方向只能向下：controller → service → mapper");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("entity 不得依赖上层（service/controller/model/mapper）")
    void entityMustStayDumb() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..entity..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..service..", "..controller..", "..model..", "..mapper..")
                .because("实体是数据库行的镜像，不该知道自己被谁使用");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("controller 不得把 entity 当返回值")
    void controllerMustNotReturnEntities() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..entity..")
                .because("实体带 conversation_id、recalled_by 等内部字段，直接序列化等于全量暴露");
        rule.check(CLASSES);
    }
}
