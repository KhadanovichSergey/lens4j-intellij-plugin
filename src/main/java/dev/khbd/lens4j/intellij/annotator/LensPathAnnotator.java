package dev.khbd.lens4j.intellij.annotator;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralValue;
import dev.khbd.lens4j.intellij.common.LensPsiUtil;
import dev.khbd.lens4j.intellij.common.path.Path;
import dev.khbd.lens4j.intellij.common.path.PathParser;
import dev.khbd.lens4j.intellij.common.path.PathVisitor;
import dev.khbd.lens4j.intellij.common.path.Point;
import dev.khbd.lens4j.intellij.common.path.Property;
import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;

/**
 * @author Sergei_Khadanovich
 */
public class LensPathAnnotator implements Annotator {

    @Override
    public void annotate(PsiElement element, AnnotationHolder holder) {
        if (!LensPsiUtil.LENS_PATH_PATTERN.accepts(element)) {
            return;
        }

        PsiLiteralValue literalValue = (PsiLiteralValue) element;
        LensPsiUtil.getStringValue(literalValue)
                .ifPresent(annotatePathF(element.getTextRange(), holder));
    }

    private Consumer<String> annotatePathF(TextRange originalElementRange, AnnotationHolder holder) {
        return pathStr -> {
            if (pathStr.isBlank()) {
                return;
            }
            Path path = new PathParser().parse(pathStr).getCorrectPathPrefix();

            path.visit(new PathPartAnnotatorVisitor(originalElementRange, holder));
        };
    }

    @RequiredArgsConstructor
    private static class PathPartAnnotatorVisitor implements PathVisitor {

        private final TextRange originalElementRange;
        private final AnnotationHolder holder;

        @Override
        public void visitProperty(Property property) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(property.getTextRange().shiftRight(originalElementRange.getStartOffset() + 1))
                    .textAttributes(DefaultLanguageHighlighterColors.INSTANCE_FIELD)
                    .create();
        }

        @Override
        public void visitPoint(Point point) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(point.getTextRange().shiftRight(originalElementRange.getStartOffset() + 1))
                    .textAttributes(DefaultLanguageHighlighterColors.DOT)
                    .create();
        }
    }
}
