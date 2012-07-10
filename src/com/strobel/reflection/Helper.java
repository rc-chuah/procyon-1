package com.strobel.reflection;

import com.strobel.core.StringUtilities;
import com.strobel.core.VerifyArgument;
import com.strobel.util.TypeUtils;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

import javax.lang.model.type.TypeKind;
import java.util.*;

import static com.sun.tools.javac.util.ListBuffer.lb;

/**
 * @author Mike Strobel
 */
final class Helper {
    private Helper() {}

    public static boolean overrides(final MethodInfo baseMethod, final MethodInfo ancestorMethod) {
        if (ancestorMethod.isFinal() || ancestorMethod.isPrivate()) {
            return false;
        }

        final int baseModifier = baseMethod.getModifiers() & Flags.AccessFlags;
        final int ancestorModifier = ancestorMethod.getModifiers() & Flags.AccessFlags;

        if (baseModifier != ancestorModifier) {
            return false;
        }

        if (!StringUtilities.equals(baseMethod.getName(), ancestorMethod.getName())) {
            return false;
        }

        final ParameterList baseParameters = baseMethod.getParameters();
        final ParameterList ancestorParameters = ancestorMethod.getParameters();

        if (baseParameters.size() != ancestorParameters.size()) {
            return false;
        }

        if (!baseMethod.getDeclaringType().isSubType(ancestorMethod.getDeclaringType())) {
            return false;
        }

        if (!ancestorMethod.getReturnType().isAssignableFrom(baseMethod.getReturnType())) {
            return false;
        }

        for (int i = 0, n = ancestorParameters.size(); i < n; i++) {
            final ParameterInfo baseParameter = baseParameters.get(i);
            final ParameterInfo ancestorParameter = ancestorParameters.get(i);

            if (!TypeUtils.areEquivalent(baseParameter.getParameterType(), ancestorParameter.getParameterType())) {
                return false;
            }
        }

        return true;
    }

    private static boolean isOverridableIn(final MethodInfo method, final Type origin) {
        VerifyArgument.notNull(method, "method");
        VerifyArgument.notNull(origin, "origin");

        switch (method.getModifiers() & Flags.AccessFlags) {
            case 0:
                // for package private: can only override in the same package.
                return method.getDeclaringType().getPackage() == origin.getPackage() &&
                       !origin.isInterface();
            case Flags.PRIVATE:
                return false;
            case Flags.PUBLIC:
                return true;
            case Flags.PROTECTED:
                return !origin.isInterface();
            default:
                return false;
        }
    }

    public static boolean overrides(final MethodInfo method, final MethodInfo other, final boolean checkResult) {
        if (method == other) {
            return true;
        }

        if (!isOverridableIn(other, method.getDeclaringType())) {
            return false;
        }

        // Check for a direct implementation
        if (asSuper(method.getDeclaringType(), other.getDeclaringType()) != null) {
            if (isSubSignature(method, other)) {
                if (!checkResult) {
                    return true;
                }
                if (returnTypeSubstitutable(method, other)) {
                    return true;
                }
            }
        }

        // Check for an inherited implementation

        //noinspection SimplifiableIfStatement
        if (method.isAbstract() || !other.isAbstract()) {
            return false;
        }

        return isSubSignature(method, other) &&
               (!checkResult || resultSubtype(method, other));
    }

    public static boolean resultSubtype(final MethodInfo t, final MethodInfo s) {
        final TypeList tVars = t.getTypeArguments();
        final TypeList sVars = s.getTypeArguments();
        final Type tReturn = t.getReturnType();
        final Type sReturn = substitute(s.getReturnType(), sVars, tVars);
        return covariantReturnType(tReturn, sReturn);
    }

    public static boolean covariantReturnType(final Type t, final Type s) {
        return
            isSameType(t, s) ||
            !t.isPrimitive() &&
            !s.isPrimitive() &&
            isAssignable(t, s);
    }

    public static boolean isAssignable(final Type t, final Type s) {
        if (TypeUtils.isAutoUnboxed(s)) {
            return isAssignable(t, TypeUtils.getUnderlyingPrimitive(s));
        }

        return isConvertible(t, s);
    }

    public static boolean isConvertible(final Type t, final Type s) {
        final boolean tPrimitive = t.isPrimitive();
        final boolean sPrimitive = s.isPrimitive();

        if (tPrimitive == sPrimitive) {
            return isSubtypeUnchecked(t, s);
        }

        return tPrimitive
               ? isSubtype(TypeUtils.getBoxedTypeOrSelf(t), s)
               : isSubtype(TypeUtils.getUnderlyingPrimitiveOrSelf(t), s);
    }

    public static boolean isSubtypeUnchecked(final Type t, final Type s) {
        if (t.isArray() && s.isArray()) {
            if (t.getElementType().isPrimitive()) {
                return isSameType(elementType(t), elementType(s));
            }
            return isSubtypeUnchecked(elementType(t), elementType(s));
        }
        else if (isSubtype(t, s)) {
            return true;
        }
        else if (t.isGenericParameter()) {
            return isSubtypeUnchecked(t.getUpperBound(), s);
        }
        else if (!s.isGenericType() || !s.getTypeBindings().hasUnboundParameters()) {
            final Type t2 = asSuper(t, s);
            if (t2 != null && t2.isGenericType() && t2.getTypeBindings().hasUnboundParameters()) {
                return true;
            }
        }
        return false;
    }

    public static boolean returnTypeSubstitutable(final MethodInfo r1, final MethodInfo r2) {
        if (hasSameArgs(r1, r2)) {
            return resultSubtype(r1, r2);
        }

        return covariantReturnType(
            r1.getReturnType(),
            erasure(r2.getReturnType())
        );
    }

    public static boolean isSubSignature(final MethodInfo t, final MethodInfo p) {
        return hasSameArgs(t, p) ||
               containsTypeEquivalent(t.getParameters().getParameterTypes(), erasure(p.getParameters().getParameterTypes()));
    }

    public static boolean hasSameArgs(final MethodInfo t, final MethodInfo p) {
        return containsTypeEquivalent(
            t.getParameters().getParameterTypes(),
            p.getParameters().getParameterTypes()
        );
    }

    public static boolean hasSameArgs(final TypeList t, final TypeList p) {
        return containsTypeEquivalent(t, p);
    }

    public static Type asSuper(final Type type, final Type other) {
        return AsSuperVisitor.visit(type, other);
    }

    public static boolean isSuperType(final Type type, final Type other) {
        return type == other || other == Type.Bottom || isSubtype(other, type);
    }

    public static boolean isSubtype(final Type t, final Type p) {
        return isSubtype(t, p, true);
    }

    public static boolean isSubtypeNoCapture(final Type t, final Type p) {
        return isSubtype(t, p, false);
    }

    public static boolean isSubtype(final Type t, final Type p, final boolean capture) {
        if (t == p) {
            return true;
        }

        if (p.isCompoundType()) {
            final Type baseType = p.getBaseType();

            if (baseType != null && !isSubtype(t, baseType, capture)) {
                return false;
            }

            final TypeList interfaces = p.getExplicitInterfaces();

            for (int i = 0, n = interfaces.size(); i < n; i++) {
                final Type type = interfaces.get(i);
                if (!isSubtype(t, type, capture)) {
                    return false;
                }
            }

            return true;
        }

        final Type lower = lowerBound(p);

        if (p != lower) {
            return isSubtype(capture ? capture(t) : t, lower, false);
        }

        return IsSubtypeRelation.visit(capture ? capture(t) : t, p);
    }

/*
    private static List<Type> freshTypeVariables(final List<Type> types) {
        final ListBuffer<Type> result = lb();
        for (final Type t : types) {
            if (t.isWildcardType()) {
                final Type bound = t.getUpperBound();
                result.append(new CapturedType(Type.Bottom, bound, Type.Bottom, t));
            }
            else {
                result.append(t);
            }
        }
        return result.toList();
    }
*/

    private static TypeList freshTypeVariables(final TypeList types) {
        final ListBuffer<Type> result = lb();
        for (final Type t : types) {
            if (t.isWildcardType()) {
                final Type bound = t.getUpperBound();
                result.append(new CapturedType(Type.Bottom, bound, Type.Bottom, t));
            }
            else {
                result.append(t);
            }
        }
        return new TypeList(result.toList());
    }

    public static Type capture(Type t) {
        if (t.isGenericParameter() || t.isWildcardType() || t.isPrimitive() || t.isArray() || t == Type.Bottom || t == Type.NullType) {
            return t;
        }

        final Type declaringType = t.getDeclaringType();

        if (declaringType != Type.Bottom && declaringType != null) {
            final Type capturedDeclaringType = capture(declaringType);

            if (capturedDeclaringType != declaringType) {
                final Type memberType = capturedDeclaringType.getNestedType(t.getFullName());
                t = substitute(memberType, memberType.getGenericTypeParameters(), t.getTypeArguments());
            }
        }

        if (!t.isGenericType()) {
            return t;
        }

        final Type G = t.getGenericTypeDefinition();
        final TypeList A = G.getTypeArguments();
        final TypeList T = t.getTypeArguments();
        final TypeList S = freshTypeVariables(T);

        List<Type> currentA = List.from(A.toArray());
        List<Type> currentT = List.from(T.toArray());
        List<Type> currentS = List.from(S.toArray());

        boolean captured = false;
        while (!currentA.isEmpty() &&
               !currentT.isEmpty() &&
               !currentS.isEmpty()) {

            if (currentS.head != currentT.head) {
                captured = true;

                final WildcardType Ti = (WildcardType)currentT.head;
                Type Ui = currentA.head.getUpperBound();
                CapturedType Si = (CapturedType)currentS.head;

                if (Ui == null) {
                    Ui = Types.Object;
                }

                if (Ti.isUnbound()) {
                    currentS.head = Si = new CapturedType(
                        Si.getDeclaringType(),
                        substitute(Ui, A, S),
                        Type.Bottom,
                        Si.getWildcard()
                    );
                }
                else if (Ti.isExtendsBound()) {
                    currentS.head = Si = new CapturedType(
                        Si.getDeclaringType(),
                        glb(Ti.getUpperBound(), substitute(Ui, A, S)),
                        Type.Bottom,
                        Si.getWildcard()
                    );
                }
                else {
                    currentS.head = Si = new CapturedType(
                        Si.getDeclaringType(),
                        substitute(Ui, A, S),
                        Ti.getLowerBound(),
                        Si.getWildcard()
                    );
                }

                if (Si.getUpperBound() == Si.getLowerBound()) {
                    currentS.head = Si.getUpperBound();
                }
            }

            currentA = currentA.tail;
            currentT = currentT.tail;
            currentS = currentS.tail;
        }

        if (!currentA.isEmpty() || !currentT.isEmpty() || !currentS.isEmpty()) {
            return erasure(t); // some "rare" type involved
        }

        if (captured) {
            return t.makeGenericType(S.toArray());
        }
        else {
            return t;
        }
    }

    static boolean containsType(List<Type> ts, List<Type> ss) {
        while (ts.nonEmpty() && ss.nonEmpty() && containsType(ts.head, ss.head)) {
            ts = ts.tail;
            ss = ss.tail;
        }
        return ts.isEmpty() && ss.isEmpty();
    }

    static boolean containsType(final TypeList ts, final TypeList ss) {
        if (ts.size() != ss.size()) {
            return false;
        }

        if (ts.isEmpty()) {
            return true;
        }

        for (int i = 0, n = ts.size(); i < n; i++) {
            if (!containsType(ts.get(i), ss.get(i))) {
                return false;
            }
        }

        return true;
    }

    static boolean containsType(final Type t, final Type p) {
        return ContainsTypeRelation.visit(t, p);
    }

    static boolean containsTypeEquivalent(List<Type> ts, List<Type> tp) {
        while (ts.nonEmpty() && tp.nonEmpty() && containsTypeEquivalent(ts.head, tp.head)) {
            ts = ts.tail;
            tp = tp.tail;
        }
        return ts.isEmpty() && tp.isEmpty();
    }

    static boolean containsTypeEquivalent(final TypeList ts, final TypeList tp) {
        if (ts.size() != tp.size()) {
            return false;
        }

        if (ts.isEmpty()) {
            return true;
        }

        for (int i = 0, n = ts.size(); i < n; i++) {
            if (!containsTypeEquivalent(ts.get(i), tp.get(i))) {
                return false;
            }
        }

        return true;
    }

    public static TypeList map(final TypeList ts, final TypeMapping f) {
        if (ts.isEmpty()) {
            return TypeList.empty();
        }

        Type[] results = null;

        for (int i = 0, n = ts.size(); i < n; i++) {
            final Type t = ts.get(i);
            final Type r = f.apply(t);

            if (r != t) {
                if (results == null) {
                    results = ts.toArray();
                }
                results[i] = r;
            }
        }

        if (results != null) {
            return new TypeList(results);
        }

        return ts;
    }

    private static boolean containsTypeEquivalent(final Type t, final Type p) {
        return isSameType(t, p) || // shortcut
               containsType(t, p) && containsType(p, t);
    }

    public static boolean areSameTypes(final TypeList ts, final TypeList tp) {
        if (ts.size() != tp.size()) {
            return false;
        }

        if (ts.isEmpty()) {
            return true;
        }

        for (int i = 0, n = ts.size(); i < n; i++) {
            if (!isSameType(ts.get(i), tp.get(i))) {
                return false;
            }
        }

        return true;
    }

    public static boolean isSameType(final Type t, final Type p) {
        return IsSameTypeRelation.visit(t, p);
    }

    public static boolean isCaptureOf(final Type p, final Type t) {
        return p.isGenericParameter() &&
               p instanceof ICapturedType &&
               isSameWildcard(t, ((ICapturedType)p).getWildcard());
    }

    public static boolean isSameWildcard(final Type t, final Type p) {
        if (!p.isWildcardType() || !t.isWildcardType()) {
            return false;
        }

        if (p.isUnbound()) {
            return t.isUnbound();
        }

        if (p.isSuperBound()) {
            return t.isSuperBound() &&
                   isSameType(p.getLowerBound(), t.getLowerBound());
        }

        return p.isExtendsBound() &&
               t.isExtendsBound() &&
               isSameType(p.getUpperBound(), t.getUpperBound());
    }

    public static Type glb(final Type t, final Type p) {
        if (p == null) {
            return t;
        }
        else if (t.isPrimitive() || p.isPrimitive()) {
            return null;
        }
        else if (isSubtypeNoCapture(t, p)) {
            return t;
        }
        else if (isSubtypeNoCapture(p, t)) {
            return p;
        }

        final List<Type> closure = union(closure(t), closure(p));
        final List<Type> bounds = closureMin(closure);

        if (bounds.isEmpty()) {             // length == 0
            return Types.Object;
        }
        else if (bounds.tail.isEmpty()) { // length == 1
            return bounds.head;
        }
        else {                            // length > 1
            int classCount = 0;
            for (final Type bound : bounds) {
                if (!bound.isInterface()) {
                    classCount++;
                }
            }
            if (classCount > 1) {
                throw new AssertionError();
            }
        }

        Type baseClass = Types.Object;
        List<Type> interfaces = List.nil();

        for (final Type bound : bounds) {
            if (bound.isInterface()) {
                interfaces = interfaces.append(bound);
            }
            else {
                baseClass = bound;
            }
        }

        return Type.makeCompoundType(
            baseClass,
            Type.list(interfaces)
        );
    }

    public static Type elementType(final Type t) {
        if (t.isArray()) {
            return t.getElementType();
        }
        if (t.isWildcardType()) {
            return elementType(upperBound(t));
        }
        return null;
    }

    public static Type<?> upperBound(final Type<?> t) {
        return UpperBoundVisitor.visit(t);
    }

    public static Type lowerBound(final Type t) {
        return LowerBoundVisitor.visit(t);
    }

    public static TypeList erasure(final TypeList ts) {
        return map(ts, ErasureFunctor);
    }

    public static Type erasureRecursive(final Type t) {
        return erasure(t, true);
    }

    public static TypeList erasureRecursive(final TypeList ts) {
        return map(ts, ErasureRecursiveFunctor);
    }

    public static Type erasure(final Type t) {
        return erasure(t, false);
    }

    public static Type substitute(final Type type, final List<Type> genericParameters, final List<Type> typeArguments) {
        return SubstitutingBinder.visit(
            type,
            TypeBindings.create(
                Type.list(genericParameters),
                Type.list(typeArguments)
            )
        );
    }

    public static Type substitute(final Type type, final TypeList genericParameters, final TypeList typeArguments) {
        return SubstitutingBinder.visit(
            type,
            TypeBindings.create(genericParameters, typeArguments)
        );
    }

    public static Type substitute(final Type type, final TypeBindings bindings) {
        return SubstitutingBinder.visit(type, bindings);
    }

    private static Type erasure(final Type t, final boolean recurse) {
        if (t.isPrimitive()) {
            return t;  // fast special case
        }
        else {
            return ErasureVisitor.visit(t, recurse);
        }
    }

    public static List<Type> interfaces(final Type type) {
        return InterfacesVisitor.visit(type);
    }

    public static int rank(final Type t) {
        if (t.isPrimitive() || t.isWildcardType() || t.isArray() || t == Type.Bottom || t == Type.NullType) {
            throw new AssertionError();
        }

        if (t == Types.Object) {
            return 0;
        }

        int r = rank(superType(t));

        for (List<Type> l = interfaces(t);
             l.nonEmpty();
             l = l.tail) {

            final int headRank = rank(l.head);

            if (headRank > r) {
                r = headRank;
            }
        }

        return r + 1;
    }

    public static boolean precedes(final Type origin, final Type other) {
        if (origin == other) {
            return false;
        }

        if (origin.isGenericParameter() && other.isGenericParameter()) {
            return isSubtype(origin, other);
        }

        final boolean originIsClass = !origin.isWildcardType() &&
                                      !origin.isPrimitive() &&
                                      !origin.isArray() &&
                                      origin != Type.Bottom &&
                                      origin != Type.NullType;

        final boolean otherIsClass = !other.isWildcardType() &&
                                     !other.isPrimitive() &&
                                     !other.isArray() &&
                                     other != Type.Bottom &&
                                     other != Type.NullType;

        if (originIsClass && otherIsClass) {
            return rank(other) < rank(origin) ||
                   rank(other) == rank(origin) &&
                   other.getFullName().compareTo(origin.getFullName()) < 0;
        }

        return origin.isGenericParameter();
    }

    public static List<Type> union(final List<Type> cl1, final List<Type> cl2) {
        if (cl1.isEmpty()) {
            return cl2;
        }
        else if (cl2.isEmpty()) {
            return cl1;
        }
        else if (precedes(cl1.head, cl2.head)) {
            return union(cl1.tail, cl2).prepend(cl1.head);
        }
        else if (precedes(cl2.head, cl1.head)) {
            return union(cl1, cl2.tail).prepend(cl2.head);
        }
        else {
            return union(cl1.tail, cl2.tail).prepend(cl1.head);
        }
    }

    private final static TypeMapping ErasureFunctor = new TypeMapping("erasure") {
        public Type apply(final Type t) { return erasure(t); }
    };

    private final static TypeMapping ErasureRecursiveFunctor = new TypeMapping("erasureRecursive") {
        public Type apply(final Type t) { return erasureRecursive(t); }
    };

    private final static TypeBinder SubstitutingBinder = new TypeBinder();

    private final static TypeVisitor<Type, Type> AsSuperVisitor = new SimpleVisitor<Type, Type>() {
        @Override
        public Type visitClassType(final Type t, final Type p) {
            if (t == p) {
                return t;
            }

            final Type superType = t.getBaseType();

            if (superType != null && !superType.isInterface()) {
                final Type ancestor = asSuper(superType, p);
                if (ancestor != null) {
                    return ancestor;
                }
            }

            final TypeList interfaces = t.getExplicitInterfaces();

            for (int i = 0, n = interfaces.size(); i < n; i++) {
                final Type ancestor = asSuper(interfaces.get(i), p);
                if (ancestor != null) {
                    return ancestor;
                }
            }

            return null;
        }

        @Override
        public Type visitPrimitiveType(final Type t, final Type p) {
            if (t == p) {
                return t;
            }
            return null;
        }

        @Override
        public Type visitTypeParameter(final Type t, final Type p) {
            if (t == p) {
                return t;
            }
            return asSuper(t.getUpperBound(), p);
        }

        @Override
        public Type visitArrayType(final Type t, final Type p) {
            return isSubtype(t, p) ? p : null;
        }

        @Override
        public Type visitType(final Type t, final Type p) {
            return super.visitType(t, p);
        }
    };

    private final static TypeRelation IsSameTypeRelation = new TypeRelation() {
        @Override
        public Boolean visitCapturedType(final Type t, final Type p) {
            return super.visitCapturedType(t, p);
        }

        @Override
        public Boolean visitClassType(final Type type, final Type parameter) {
            return super.visitClassType(type, parameter);
        }

        @Override
        public Boolean visitPrimitiveType(final Type type, final Type parameter) {
            return type == parameter ? Boolean.TRUE : Boolean.FALSE;
        }

        @Override
        public Boolean visitTypeParameter(final Type type, final Type parameter) {
            return type.getFullName().equals(parameter.getFullName()) &&
                   type.getDeclaringType() == parameter.getDeclaringType() &&
                   visit(type.getUpperBound(), parameter.getUpperBound());
        }

        @Override
        public Boolean visitWildcardType(final Type type, final Type parameter) {
            return parameter.isSuperBound() &&
                   !parameter.isExtendsBound() &&
                   visit(type, upperBound(parameter));
        }

        @Override
        public Boolean visitArrayType(final Type type, final Type parameter) {
            return super.visitArrayType(type, parameter);
        }

        @Override
        public Boolean visitType(final Type type, final Type parameter) {
            return type == parameter;
        }
    };

    private final static TypeMapper<Void> UpperBoundVisitor = new TypeMapper<Void>() {
        @Override
        public Type visitWildcardType(final Type t, final Void ignored) {
            if (t.isSuperBound()) {
                final Type lowerBound = t.getLowerBound();

                if (lowerBound.isExtendsBound()) {
                    return visit(lowerBound.getUpperBound());
                }

                return Types.Object;
            }
            else {
                return visit(t.getUpperBound());
            }
        }

        @Override
        public Type visitCapturedType(final Type t, final Void ignored) {
            return visit(t.getUpperBound());
        }
    };

    private final static TypeMapper<Void> LowerBoundVisitor = new TypeMapper<Void>() {
        @Override
        public Type visitWildcardType(final Type t, final Void ignored) {
            return t.isExtendsBound() ? Type.Bottom : visit(t.getLowerBound());
        }

        @Override
        public Type visitCapturedType(final Type t, final Void ignored) {
            return visit(t.getLowerBound());
        }
    };

    private final static TypeMapper<Boolean> ErasureVisitor = new TypeMapper<Boolean>() {
        public Type visitType(final Type t, final Boolean recurse) {
            if (t.isPrimitive()) {
                return t;  // fast special case
            }
            else {
                return (recurse ? ErasureRecursiveFunctor : ErasureFunctor).apply(t);
            }
        }

        @Override
        public Type visitWildcardType(final Type t, final Boolean recurse) {
            return erasure(upperBound(t), recurse);
        }

        @Override
        public Type<?> visitClassType(final Type<?> t, final Boolean recurse) {
            return Type.of(t.getErasedClass());
        }

        @Override
        public Type visitTypeParameter(final Type t, final Boolean recurse) {
            return erasure(t.getUpperBound(), recurse);
        }
    };

    private final static TypeRelation ContainsTypeRelation = new TypeRelation() {

        private Type U(Type t) {
            while (t.isWildcardType()) {
                if (t.isSuperBound()) {
                    final Type lowerBound = t.getLowerBound();
                    if (lowerBound.isExtendsBound()) {
                        return lowerBound.getUpperBound();
                    }
                    return Types.Object;
                }
                t = t.getUpperBound();
            }
            return t;
        }

        private Type L(Type t) {
            while (t.isWildcardType()) {
                if (t.isExtendsBound()) {
                    return Type.Bottom;
                }
                else {
                    t = t.getLowerBound();
                }
            }
            return t;
        }

        public Boolean visitType(final Type t, final Type p) {
            return isSameType(t, p);
        }

        @Override
        public Boolean visitWildcardType(final Type t, final Type p) {
            return isSameWildcard(t, p) ||
                   isCaptureOf(p, t) ||
                   ((t.isExtendsBound() || isSubtypeNoCapture(L(t), lowerBound(p))) &&
                    (t.isSuperBound() || isSubtypeNoCapture(upperBound(p), U(t))));
        }
    };

    private final static UnaryTypeVisitor<List<Type>> InterfacesVisitor = new UnaryTypeVisitor<List<Type>>() {
        public List<Type> visitType(final Type t, final Void ignored) {
            return List.nil();
        }

        @Override
        public List<Type> visitClassType(final Type t, final Void ignored) {
            final TypeList interfaces = t.getExplicitInterfaces();

            if (interfaces.isEmpty()) {
                return List.nil();
            }

            return List.from(t.getExplicitInterfaces().toArray());
        }

        @Override
        public List<Type> visitTypeParameter(final Type t, final Void ignored) {
            final Type upperBound = t.getUpperBound();

            if (upperBound.isCompoundType()) {
                return interfaces(upperBound);
            }

            if (upperBound.isInterface()) {
                return List.of(upperBound);
            }

            return List.nil();
        }

        @Override
        public List<Type> visitWildcardType(final Type<?> type, final Void parameter) {
            return visit(type.getUpperBound());
        }
    };

    public static Type superType(final Type t) {
        return SuperTypeVisitor.visit(t);
    }

    private final static UnaryTypeVisitor<Type> SuperTypeVisitor = new UnaryTypeVisitor<Type>() {

        public Type visitType(final Type t, final Void ignored) {
            // A note on wildcards: there is no good way to
            // determine a super type for a super-bounded wildcard.
            return null;
        }

        @Override
        public Type visitClassType(final Type t, final Void ignored) {
            return t.getBaseType();
        }

        @Override
        public Type visitTypeParameter(final Type t, final Void ignored) {
            final Type bound = t.getUpperBound();

            if (!bound.isCompoundType() && !bound.isInterface()) {
                return bound;
            }

            return superType(bound);
        }

        @Override
        public Type visitArrayType(final Type t, final Void ignored) {
            final Type elementType = t.getElementType();

            if (elementType.isPrimitive() || isSameType(elementType, Types.Object)) {
                return arraySuperType();
            }
            else {
                return new ArrayType(superType(elementType));
            }
        }
    };

    private final static TypeRelation IsSubtypeRelation = new TypeRelation() {
        @Override
        public Boolean visitPrimitiveType(final Type t, final Type p) {
            if (t == p) {
                return true;
            }

            if (t == PrimitiveTypes.Byte) {
                return p == PrimitiveTypes.Character ||
                       p == PrimitiveTypes.Short;
            }

            if (t == PrimitiveTypes.Character) {
                return p == PrimitiveTypes.Short ||
                       p == PrimitiveTypes.Integer;
            }

            if (t == PrimitiveTypes.Short) {
                return p == PrimitiveTypes.Integer ||
                       p == PrimitiveTypes.Long ||
                       p == PrimitiveTypes.Float ||
                       p == PrimitiveTypes.Double;
            }

            if (t == PrimitiveTypes.Integer) {
                return p == PrimitiveTypes.Long ||
                       p == PrimitiveTypes.Float ||
                       p == PrimitiveTypes.Double;
            }

            if (t == PrimitiveTypes.Long) {
                return p == PrimitiveTypes.Float ||
                       p == PrimitiveTypes.Double;
            }

            if (t == PrimitiveTypes.Float) {
                return p == PrimitiveTypes.Double;
            }

            return Boolean.FALSE;
        }

        public Boolean visitType(final Type t, final Type s) {
            if (t.isGenericParameter()) {
                return isSubtypeNoCapture(t.getUpperBound(), s);
            }
            return Boolean.FALSE;
        }

        private final Set<TypePair> cache = new HashSet<>();

        private boolean containsTypeRecursive(final Type t, final Type s) {
            final TypePair pair = new TypePair(t, s);
            if (cache.add(pair)) {
                try {
                    return containsType(
                        t.getTypeArguments(),
                        s.getTypeArguments()
                    );
                }
                finally {
                    cache.remove(pair);
                }
            }
            else {
                return containsType(
                    t.getTypeArguments(),
                    rewriteSupers(s).getTypeArguments()
                );
            }
        }

        private Type rewriteSupers(final Type t) {
            if (!t.isGenericType()) {
                return t;
            }

            final ListBuffer<Type> from = lb();
            final ListBuffer<Type> to = lb();

            adaptSelf(t, from, to);

            if (from.isEmpty()) {
                return t;
            }

            final ListBuffer<Type> rewrite = lb();
            boolean changed = false;
            for (final Type orig : to.toList()) {
                Type<?> s = rewriteSupers(orig);
                if (s.isSuperBound() && !s.isExtendsBound()) {
                    s = new WildcardType<>(
                        Types.Object,
                        Type.Bottom
                    );
                    changed = true;
                }
                else if (s != orig) {
                    s = new WildcardType<>(
                        upperBound(s),
                        Type.Bottom
                    );
                    changed = true;
                }
                rewrite.append(s);
            }
            if (changed) {
                return substitute(t.getGenericTypeDefinition(), from.toList(), rewrite.toList());
            }
            else {
                return t;
            }
        }

        @Override
        public Boolean visitClassType(final Type t, final Type s) {
            final Type asSuper = asSuper(t, s);
            return asSuper != null
                   && asSuper == s
                   // You're not allowed to write
                   //     Vector<Object> vec = new Vector<String>();
                   // But with wildcards you can write
                   //     Vector<? extends Object> vec = new Vector<String>();
                   // which means that subtype checking must be done
                   // here instead of same-Type checking (via containsType).
                   && (!s.isGenericParameter() || containsTypeRecursive(s, asSuper))
                   && isSubtypeNoCapture(
                asSuper.getDeclaringType(),
                s.getDeclaringType()
            );
        }

        @Override
        public Boolean visitArrayType(final Type t, final Type s) {
            final Type elementType = t.getElementType();

            if (elementType.isPrimitive()) {
                return isSameType(elementType, elementType(s));
            }

            return isSubtypeNoCapture(elementType, elementType(s));
        }
    };

    public static void adapt(
        final Type source,
        final Type target,
        final ListBuffer<Type> from,
        final ListBuffer<Type> to)
        throws AdaptFailure {

        new Adapter(from, to).adapt(source, target);
    }

    @SuppressWarnings("PackageVisibleField")
    private final static class Adapter extends SimpleVisitor<Type, Void> {

        ListBuffer<Type> from;
        ListBuffer<Type> to;
        Map<Type, Type> mapping;

        Adapter(final ListBuffer<Type> from, final ListBuffer<Type> to) {
            this.from = from;
            this.to = to;
            mapping = new HashMap<>();
        }

        public void adapt(final Type source, final Type target)
            throws AdaptFailure {
            visit(source, target);
            List<Type> fromList = from.toList();
            List<Type> toList = to.toList();
            while (!fromList.isEmpty()) {
                final Type t = mapping.get(fromList.head);
                if (toList.head != t) {
                    toList.head = t;
                }
                fromList = fromList.tail;
                toList = toList.tail;
            }
        }

        @Override
        public Void visitClassType(final Type source, final Type target)
            throws AdaptFailure {

            adaptRecursive(
                source.getTypeArguments(),
                target.getTypeArguments()
            );

            return null;
        }

        @Override
        public Void visitArrayType(final Type source, final Type target)
            throws AdaptFailure {
            adaptRecursive(elementType(source), elementType(target));
            return null;
        }

        @Override
        public Void visitWildcardType(final Type source, final Type target)
            throws AdaptFailure {
            if (source.isExtendsBound()) {
                adaptRecursive(upperBound(source), upperBound(target));
            }
            else if (source.isSuperBound()) {
                adaptRecursive(lowerBound(source), lowerBound(target));
            }
            return null;
        }

        @Override
        public Void visitTypeParameter(final Type source, final Type target)
            throws AdaptFailure {
            // Check to see if there is
            // already a mapping for $source$, in which case
            // the old mapping will be merged with the new
            Type val = mapping.get(source);
            if (val != null) {
                if (val.isSuperBound() && target.isSuperBound()) {
                    val = isSubtype(lowerBound(val), lowerBound(target))
                          ? target : val;
                }
                else if (val.isExtendsBound() && target.isExtendsBound()) {
                    val = isSubtype(upperBound(val), upperBound(target))
                          ? val : target;
                }
                else if (!isSameType(val, target)) {
                    throw new AdaptFailure();
                }
            }
            else {
                val = target;
                from.append(source);
                to.append(target);
            }
            mapping.put(source, val);
            return null;
        }

        @Override
        public Void visitType(final Type source, final Type target) {
            return null;
        }

        private final Set<TypePair> cache = new HashSet<>();

        private void adaptRecursive(final Type source, final Type target) {
            final TypePair pair = new TypePair(source, target);
            if (cache.add(pair)) {
                try {
                    visit(source, target);
                }
                finally {
                    cache.remove(pair);
                }
            }
        }

        private void adaptRecursive(final TypeList source, final TypeList target) throws AdaptFailure {
            if (source.size() != target.size()) {
                return;
            }

            for (int i = 0, n = source.size(); i < n; i++) {
                adapt(source.get(i), target.get(i));
            }
        }
    }

    public static class AdaptFailure extends RuntimeException {
        static final long serialVersionUID = -7490231548272701566L;
    }

    private static void adaptSelf(
        final Type t,
        final ListBuffer<Type> from,
        final ListBuffer<Type> to) {
        try {
            //if (t.getGenericTypeDefinition() != t)
            adapt(t.getGenericTypeDefinition(), t, from, to);
        }
        catch (AdaptFailure ex) {
            // Adapt should never fail calculating a mapping from
            // t.getGenericTypeDefinition() to t as there can be no merge problem.
            throw new AssertionError(ex);
        }
    }

    public static int hashCode(final Type t) {
        return HashCodeVisitor.visit(t);
    }

    private static final UnaryTypeVisitor<Integer> HashCodeVisitor = new UnaryTypeVisitor<Integer>() {

        public Integer visitType(final Type t, final Void ignored) {
            return t.getKind().hashCode();
        }

        @Override
        public Integer visitClassType(final Type t, final Void ignored) {
            int result = 0;

            final Type declaringType = t.getDeclaringType();

            if (declaringType != null) {
                result = visit(declaringType);
            }

            result *= 127;
            result += t.getFullName().hashCode();

            for (final Type s : t.getTypeArguments()) {
                result *= 127;
                result += visit(s);
            }

            return result;
        }

        @Override
        public Integer visitWildcardType(final Type t, final Void ignored) {
            int result = t.getKind().hashCode();
            if (t.getUpperBound() != null) {
                result *= 127;
                result += visit(t.getUpperBound());
            }
            return result;
        }

        @Override
        public Integer visitArrayType(final Type t, final Void ignored) {
            return visit(t.getElementType()) + 12;
        }

        @Override
        public Integer visitTypeParameter(final Type t, final Void ignored) {
            return System.identityHashCode(t);
        }
    };

    public static boolean isReifiable(final Type t) {
        return IsReifiableVisitor.visit(t);
    }

    private final static UnaryTypeVisitor<Boolean> IsReifiableVisitor = new UnaryTypeVisitor<Boolean>() {

        public Boolean visitType(final Type t, final Void ignored) {
            return true;
        }

        @Override
        public Boolean visitClassType(final Type t, final Void ignored) {
            if (t.isCompoundType()) {
                return Boolean.FALSE;
            }
            else {
                if (!t.isGenericType()) {
                    return Boolean.TRUE;
                }

                for (final Type p : t.getTypeArguments()) {
                    if (!p.isUnbound()) {
                        return Boolean.FALSE;
                    }
                }

                return Boolean.TRUE;
            }
        }

        @Override
        public Boolean visitArrayType(final Type t, final Void ignored) {
            return visit(t.getElementType());
        }

        @Override
        public Boolean visitTypeParameter(final Type t, final Void ignored) {
            return false;
        }
    };

    private static Type _arraySuperType = null;

    private static Type arraySuperType() {
        // initialized lazily to avoid problems during compiler startup
        if (_arraySuperType == null) {
            synchronized (Helper.class) {
                if (_arraySuperType == null) {
                    // JLS 10.8: all arrays implement Cloneable and Serializable.
                    _arraySuperType = Type.makeCompoundType(
                        Types.Object,
                        Type.list(
                            Types.Serializable,
                            Types.Cloneable
                        )
                    );
                }
            }
        }
        return _arraySuperType;
    }

    private final static Map<Type, List<Type>> closureCache = new HashMap<>();

    public static List<Type> insert(final List<Type> cl, final Type t) {
        if (cl.isEmpty() || precedes(t, cl.head)) {
            return cl.prepend(t);
        }
        else if (precedes(cl.head, t)) {
            return insert(cl.tail, t).prepend(cl.head);
        }
        else {
            return cl;
        }
    }

    private static List<Type> closureMin(List<Type> cl) {
        final ListBuffer<Type> classes = lb();
        final ListBuffer<Type> interfaces = lb();

        while (!cl.isEmpty()) {
            final Type current = cl.head;

            if (current.isInterface()) {
                interfaces.append(current);
            }
            else {
                classes.append(current);
            }

            final ListBuffer<Type> candidates = lb();

            for (final Type t : cl.tail) {
                if (!isSubtypeNoCapture(current, t)) {
                    candidates.append(t);
                }
            }

            cl = candidates.toList();
        }

        return classes.appendList(interfaces).toList();
    }

    public static List<Type> closure(final Type t) {
        List<Type> cl = closureCache.get(t);
        if (cl == null) {
            final Type st = superType(t);
            if (!t.isCompoundType()) {
                if (st.getKind() == TypeKind.DECLARED) {
                    cl = insert(closure(st), t);
                }
                else if (st.getKind() == TypeKind.TYPEVAR) {
                    cl = closure(st).prepend(t);
                }
                else {
                    cl = List.of(t);
                }
            }
            else {
                cl = closure(superType(t));
            }
            for (List<Type> l = interfaces(t); l.nonEmpty(); l = l.tail) {
                cl = union(cl, closure(l.head));
            }
            closureCache.put(t, cl);
        }
        return cl;
    }

    @SuppressWarnings("PackageVisibleField")
    final static class TypePair {
        final Type t1;
        final Type t2;

        TypePair(final Type t1, final Type t2) {
            this.t1 = t1;
            this.t2 = t2;
        }

        @Override
        public int hashCode() {
            return 127 * Helper.hashCode(t1) + Helper.hashCode(t2);
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof TypePair)) {
                return false;
            }

            final TypePair typePair = (TypePair)obj;

            return Helper.isSameType(t1, typePair.t1) &&
                   Helper.isSameType(t2, typePair.t2);
        }
    }
}