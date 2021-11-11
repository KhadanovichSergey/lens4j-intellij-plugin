package dev.khbd.lens4j.intellij.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralValue;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import dev.khbd.lens4j.common.Method;
import dev.khbd.lens4j.common.Path;
import dev.khbd.lens4j.common.PathParser;
import dev.khbd.lens4j.common.PathPart;
import dev.khbd.lens4j.common.Property;
import dev.khbd.lens4j.core.annotations.GenLenses;
import dev.khbd.lens4j.intellij.Lens4jBundle;
import dev.khbd.lens4j.intellij.common.LensPsiUtil;
import dev.khbd.lens4j.intellij.common.path.PathService;
import dev.khbd.lens4j.intellij.common.path.PsiMemberResolver;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Sergei_Khadanovich
 */
public class LensPathValidityInspection extends AbstractBaseJavaLocalInspectionTool {

    @Override
    public ProblemDescriptor[] checkClass(PsiClass psiClass, InspectionManager manager, boolean isOnTheFly) {
        if (psiClass.isInterface()) {
            return ProblemDescriptor.EMPTY_ARRAY;
        }

        List<PsiAnnotation> lenses = findLensAnnotations(psiClass);

        return checkLensAnnotations(psiClass, lenses, manager, isOnTheFly)
                .toArray(ProblemDescriptor[]::new);
    }

    private List<PsiAnnotation> findLensAnnotations(PsiClass psiClass) {
        PsiAnnotation genLens = psiClass.getAnnotation(GenLenses.class.getName());
        if (Objects.isNull(genLens)) {
            return List.of();
        }

        return findLensAnnotations(genLens);
    }

    private List<PsiAnnotation> findLensAnnotations(PsiAnnotation genLens) {
        PsiAnnotationMemberValue lenses = genLens.findAttributeValue("lenses");

        if (lenses instanceof PsiAnnotation) {
            return List.of((PsiAnnotation) lenses);
        }

        if (lenses instanceof PsiArrayInitializerMemberValue) {
            PsiArrayInitializerMemberValue initializerMemberValue = (PsiArrayInitializerMemberValue) lenses;
            PsiAnnotationMemberValue[] initializers = initializerMemberValue.getInitializers();
            return Arrays.stream(initializers)
                    .map(PsiAnnotation.class::cast)
                    .collect(Collectors.toList());
        }

        return List.of();
    }

    private List<ProblemDescriptor> checkLensAnnotations(PsiClass psiClass,
                                                         List<PsiAnnotation> lenses,
                                                         InspectionManager manager,
                                                         boolean isOnTheFly) {
        LensInspector inspector = new LensInspector(psiClass, manager, isOnTheFly);
        return lenses.stream()
                .flatMap(lens -> inspector.inspect(lens).stream())
                .collect(Collectors.toList());
    }

    @RequiredArgsConstructor
    private static class LensInspector {
        final PsiClass psiClass;
        final InspectionManager manager;
        final boolean isOnTheFly;

        List<ProblemDescriptor> inspect(PsiAnnotation lens) {
            PsiAnnotationMemberValue pathMember = lens.findAttributeValue("path");
            // Lens#path can be null, if user has not completed typing yet.
            if (Objects.isNull(pathMember)) {
                return List.of();
            }

            PsiLiteralValue literalValue = (PsiLiteralValue) pathMember;

            return LensPsiUtil.getStringValue(literalValue)
                    .flatMap(checkLensPathF(lens, literalValue))
                    .stream()
                    .collect(Collectors.toList());
        }

        Function<String, Optional<ProblemDescriptor>> checkLensPathF(PsiAnnotation lens,
                                                                     PsiLiteralValue literalValue) {
            return pathStr -> {
                if (pathStr.isBlank()) {
                    return Optional.of(pathIsBlankProblem(literalValue));
                }
                return inspectNotBlankPath(lens, literalValue, pathStr);
            };
        }

        ProblemDescriptor pathIsBlankProblem(PsiLiteralValue literalValue) {
            return manager.createProblemDescriptor(
                    literalValue,
                    Lens4jBundle.getMessage("inspection.gen.lenses.lens.path.blank"),
                    (LocalQuickFix) null,
                    ProblemHighlightType.GENERIC_ERROR,
                    isOnTheFly
            );
        }

        Optional<ProblemDescriptor> inspectNotBlankPath(PsiAnnotation lens,
                                                        PsiLiteralValue literalValue,
                                                        String pathStr) {
            PathParser parser = PathParser.getInstance();
            Path path = PathService.getInstance().getCorrectPathPrefix(parser.parse(pathStr));

            PsiMemberResolver resolver = new PsiMemberResolver(psiClass);
            path.visit(resolver);

            if (resolver.isResolved()) {
                return checkResolvedPath(lens, path, literalValue);
            }

            return deriveNotFoundProblem(resolver, literalValue);
        }

        Optional<ProblemDescriptor> checkResolvedPath(PsiAnnotation lens,
                                                      Path path,
                                                      PsiLiteralValue literalValue) {
            if (lensIsWrite(lens)) {
                PathPart lastPart = path.getLastPart();
                if (lastPart.isMethod()) {
                    return Optional.of(methodNotAllowedAtWritePosition(literalValue, (Method) lastPart));
                }
            }
            return Optional.empty();
        }

        Optional<ProblemDescriptor> deriveNotFoundProblem(PsiMemberResolver resolver,
                                                          PsiLiteralValue literalValue) {
            PathPart part = resolver.getNonResolvedPart();
            if (part.isProperty()) {
                return Optional.of(propertyNotFoundProblem(literalValue, (Property) part,
                        resolver.getLastResolvedType()));
            }
            if (part.isMethod()) {
                return Optional.of(methodNotFoundProblem(literalValue, (Method) part,
                        resolver.getLastResolvedType()));
            }
            return Optional.empty();
        }

        ProblemDescriptor propertyNotFoundProblem(PsiLiteralValue literalValue,
                                                  Property property,
                                                  PsiType type) {
            return manager.createProblemDescriptor(
                    literalValue,
                    PathService.getInstance().getPropertyNameTextRange(property).shiftRight(1),
                    Lens4jBundle.getMessage("inspection.gen.lenses.lens.path.property.not.exist",
                            property.getName(), type.getPresentableText()
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    isOnTheFly,
                    (LocalQuickFix) null
            );
        }

        ProblemDescriptor methodNotFoundProblem(PsiLiteralValue literalValue,
                                                Method method,
                                                PsiType type) {
            return manager.createProblemDescriptor(
                    literalValue,
                    PathService.getInstance().getMethodNameTextRange(method).shiftRight(1),
                    Lens4jBundle.getMessage("inspection.gen.lenses.lens.path.method.not.exist",
                            method.getName(), type.getPresentableText()
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    isOnTheFly,
                    (LocalQuickFix) null
            );
        }

        ProblemDescriptor methodNotAllowedAtWritePosition(PsiLiteralValue literalValue,
                                                          Method method) {
            return manager.createProblemDescriptor(
                    literalValue,
                    PathService.getInstance().getMethodNameTextRange(method).shiftRight(1),
                    Lens4jBundle.getMessage("inspection.gen.lenses.lens.path.method.at.write.position"),
                    ProblemHighlightType.GENERIC_ERROR,
                    isOnTheFly,
                    (LocalQuickFix) null
            );
        }

        boolean lensIsWrite(PsiAnnotation lens) {
            PsiAnnotationMemberValue typeMember = lens.findAttributeValue("type");
            if (!(typeMember instanceof PsiReferenceExpression)) {
                return false;
            }
            PsiReferenceExpression ref = (PsiReferenceExpression) typeMember;
            PsiField field = (PsiField) ref.resolve();
            if (Objects.isNull(field)) {
                return false;
            }
            return "READ_WRITE".equals(field.getName());
        }
    }

}
