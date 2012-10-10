/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection;

import com.intellij.codeInsight.daemon.GroupNames;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * User: anna
 */
public class LambdaCanBeMethReferenceInspection extends BaseJavaLocalInspectionTool {
  public static final Logger LOG = Logger.getInstance("#" + LambdaCanBeMethReferenceInspection.class.getName());

  @Nls
  @NotNull
  @Override
  public String getGroupDisplayName() {
    return GroupNames.LANGUAGE_LEVEL_SPECIFIC_GROUP_NAME;
  }

  @Nls
  @NotNull
  @Override
  public String getDisplayName() {
    return "Lambda can be replaced with method reference";
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @NotNull
  @Override
  public String getShortName() {
    return "Convert2MethodRef";
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
    return new JavaElementVisitor() {
      @Override
      public void visitLambdaExpression(PsiLambdaExpression expression) {
        super.visitLambdaExpression(expression);
        if (PsiUtil.getLanguageLevel(expression).isAtLeast(LanguageLevel.JDK_1_8)) {
          final PsiElement body = expression.getBody();
          final PsiCallExpression callExpression = canBeMethodReferenceProblem(body, expression.getParameterList().getParameters());
          if (callExpression != null) {
            holder.registerProblem(callExpression,
                                   "Can be replaced with method reference",
                                   ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ReplaceWithMethodRefFix());
          }
        }
      }
    };
  }

  @Nullable
  protected static PsiCallExpression canBeMethodReferenceProblem(@Nullable final PsiElement body, final PsiParameter[] parameters) {
    PsiCallExpression methodCall = null;
    if (body instanceof PsiCallExpression) {
      methodCall = (PsiCallExpression)body;
    }
    else if (body instanceof PsiCodeBlock) {
      final PsiStatement[] statements = ((PsiCodeBlock)body).getStatements();
      if (statements.length == 1) {
        if (statements[0] instanceof PsiReturnStatement) {
          final PsiExpression returnValue = ((PsiReturnStatement)statements[0]).getReturnValue();
          if (returnValue instanceof PsiCallExpression) {
            methodCall = (PsiCallExpression)returnValue;
          }
        }
        else if (statements[0] instanceof PsiExpressionStatement) {
          final PsiExpression expr = ((PsiExpressionStatement)statements[0]).getExpression();
          if (expr instanceof PsiCallExpression) {
            methodCall = (PsiCallExpression)expr;
          }
        }
      }
    }

    if (methodCall != null) {
      final PsiExpressionList argumentList = methodCall.getArgumentList();
      if (argumentList != null) {
        final PsiExpression[] expressions = argumentList.getExpressions();

        final PsiMethod psiMethod = methodCall.resolveMethod();
        final PsiClass containingClass;
        boolean isConstructor;
        if (psiMethod == null) {
          isConstructor = true;
          if (!(methodCall instanceof PsiNewExpression)) return null;
          final PsiJavaCodeReferenceElement classReference = ((PsiNewExpression)methodCall).getClassOrAnonymousClassReference();
          if (classReference == null) return null;
          containingClass = (PsiClass)classReference.resolve();
        }
        else {
          containingClass = psiMethod.getContainingClass();
          isConstructor = psiMethod.isConstructor();
        }
        if (containingClass == null) return null;
        boolean isReceiverType = parameters.length > 0 && LambdaUtil.isReceiverType(parameters[0].getType(), containingClass, PsiUtil
          .resolveGenericsClassInType(parameters[0].getType()).getSubstitutor());
        final boolean staticOrValidConstructorRef;
        if (isConstructor) {
          staticOrValidConstructorRef =
            (containingClass.getContainingClass() == null || containingClass.hasModifierProperty(PsiModifier.STATIC));
        }
        else {
          staticOrValidConstructorRef = psiMethod.hasModifierProperty(PsiModifier.STATIC);
        }

        final int offset = isReceiverType && !staticOrValidConstructorRef ? 1 : 0;
        if (parameters.length != expressions.length + offset) return null;

        for (int i = 0; i < expressions.length; i++) {
          PsiExpression psiExpression = expressions[i];
          if (!(psiExpression instanceof PsiReferenceExpression)) return null;
          final PsiElement resolve = ((PsiReferenceExpression)psiExpression).resolve();
          if (resolve == null) return null;
          if (parameters[i + offset] != resolve) return null;
        }

        if (offset > 0) {
          final PsiExpression qualifierExpression;
          if (methodCall instanceof PsiMethodCallExpression) {
            qualifierExpression = ((PsiMethodCallExpression)methodCall).getMethodExpression().getQualifierExpression();
          }
          else if (methodCall instanceof PsiNewExpression) {
            qualifierExpression = ((PsiNewExpression)methodCall).getQualifier();
          }
          else {
            qualifierExpression = null;
          }
          if (!(qualifierExpression instanceof PsiReferenceExpression) ||
              ((PsiReferenceExpression)qualifierExpression).resolve() != parameters[0]) {
            return null;
          }
        }
        return methodCall;
      }
    }
    return null;
  }

  @Nullable
  protected static String createMethodReferenceText(PsiElement element, final PsiParameter[] parameters) {
    String methodRefText = null;
    if (element instanceof PsiMethodCallExpression) {
      final PsiMethodCallExpression methodCall = (PsiMethodCallExpression)element;
      final PsiMethod psiMethod = methodCall.resolveMethod();
      LOG.assertTrue(psiMethod != null);
      final PsiClass containingClass = psiMethod.getContainingClass();
      LOG.assertTrue(containingClass != null);
      final PsiReferenceExpression methodExpression = methodCall.getMethodExpression();
      final PsiExpression qualifierExpression = methodExpression.getQualifierExpression();
      final String methodReferenceName = methodExpression.getReferenceName();
      if (qualifierExpression != null) {
        boolean isReceiverType = parameters.length > 0 && LambdaUtil.isReceiverType(parameters[0].getType(), containingClass, PsiUtil
          .resolveGenericsClassInType(parameters[0].getType()).getSubstitutor());
        methodRefText = (isReceiverType ? containingClass.getQualifiedName() : qualifierExpression.getText()) + "::" + methodReferenceName;
      }
      else {
        methodRefText =
          (psiMethod.hasModifierProperty(PsiModifier.STATIC) ? containingClass.getQualifiedName() : "this") + "::" + methodReferenceName;
      }
    }
    else if (element instanceof PsiNewExpression) {
      final PsiMethod constructor = ((PsiNewExpression)element).resolveConstructor();
      if (constructor != null) {
        final PsiClass containingClass = constructor.getContainingClass();
        LOG.assertTrue(containingClass != null);
        methodRefText = containingClass.getQualifiedName() + "::new";
      }
      else {
        final PsiJavaCodeReferenceElement classReference = ((PsiNewExpression)element).getClassOrAnonymousClassReference();
        if (classReference != null) {
          final JavaResolveResult resolve = classReference.advancedResolve(false);
          final PsiElement containingClass = resolve.getElement();
          if (containingClass instanceof PsiClass) {
            methodRefText = ((PsiClass)containingClass).getQualifiedName() + "::new";
          }
        }
      }
    }
    return methodRefText;
  }

  private static class ReplaceWithMethodRefFix implements LocalQuickFix {
    @NotNull
    @Override
    public String getName() {
      return "Replace lambda with method reference";
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return getName();
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      final PsiLambdaExpression lambdaExpression = PsiTreeUtil.getParentOfType(element, PsiLambdaExpression.class);
      if (lambdaExpression == null) return;
      final String methodRefText = createMethodReferenceText(element, lambdaExpression.getParameterList().getParameters());

      if (methodRefText != null) {
        final PsiExpression psiExpression =
          JavaPsiFacade.getElementFactory(project).createExpressionFromText(methodRefText, lambdaExpression);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(lambdaExpression.replace(psiExpression));
      }
    }
  }
}
