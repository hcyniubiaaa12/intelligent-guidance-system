package com.guide.admin;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.guide.async.enums.IngestStage;
import com.guide.async.enums.IngestStatus;
import com.guide.auth.enums.SensitiveWordType;
import com.guide.auth.enums.UserRole;
import com.guide.auth.enums.UserStatus;
import com.guide.auth.enums.ViolationLevel;
import com.guide.chat.enums.MessageRole;
import com.guide.chat.enums.SessionStatus;
import com.guide.feedback.enums.BucketStatus;
import com.guide.feedback.enums.FilterAction;
import com.guide.feedback.enums.ReviewStatus;
import com.guide.feedback.enums.TrackStage;
import com.guide.kb.enums.DocFailType;
import com.guide.kb.enums.DocStatus;
import com.guide.kb.enums.MappingSource;
import com.guide.kb.enums.TermSource;
import com.guide.kb.enums.TermType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 枚举字段规范校验（见《数据库设计.md》§0）：
 * 入库编码值必须是全小写，且与枚举常量名一一对应——防止 Java 与数据库两端语义漂移。
 */
class EnumConventionTest {

    /** 全部需要入库装载的枚举（新增枚举时同步登记） */
    private static final List<Class<? extends Enum<?>>> ENUM_TYPES = List.of(
            UserRole.class, UserStatus.class, SensitiveWordType.class, ViolationLevel.class,
            SessionStatus.class, MessageRole.class,
            DocStatus.class, DocFailType.class, MappingSource.class, TermType.class, TermSource.class,
            TrackStage.class, FilterAction.class, BucketStatus.class, ReviewStatus.class,
            IngestStage.class, IngestStatus.class
    );

    @Test
    void codeValuesAreLowercaseAndMatchConstantName() {
        for (Class<? extends Enum<?>> type : ENUM_TYPES) {
            for (Enum<?> constant : type.getEnumConstants()) {
                String code = codeOf(constant);
                assertTrue(code.matches("[a-z_]+"),
                        type.getSimpleName() + "." + constant.name() + " 编码值必须全小写: " + code);
                assertEquals(constant.name().toLowerCase(), code,
                        type.getSimpleName() + "." + constant.name() + " 编码值应与常量名小写形式一致");
            }
        }
    }

    @Test
    void everyEnumDeclaresExactlyOneEnumValueField() {
        for (Class<? extends Enum<?>> type : ENUM_TYPES) {
            int annotated = 0;
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(EnumValue.class)) {
                    annotated++;
                    assertEquals(String.class, field.getType(),
                            type.getSimpleName() + " 的 @EnumValue 字段必须是 String");
                }
            }
            assertEquals(1, annotated, type.getSimpleName() + " 必须且只能有一个 @EnumValue 字段");
        }
    }

    /** 反射读取 @EnumValue 标注字段的编码值 */
    private static String codeOf(Enum<?> constant) {
        for (Field field : constant.getDeclaringClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(EnumValue.class)) {
                field.setAccessible(true);
                try {
                    return (String) field.get(constant);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return fail("缺少 @EnumValue 字段: " + constant.getDeclaringClass().getName());
    }
}
