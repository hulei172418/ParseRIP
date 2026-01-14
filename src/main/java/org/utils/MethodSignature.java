package org.utils;

import soot.SootMethod;
import soot.Type;

import java.util.List;

public class MethodSignature {

    public static String getMethodSignature(SootMethod method) {
        String methodName;
        String temp;
        if ("<init>".equals(method.getName())) {
            methodName = method.getDeclaringClass().toString();
            temp = methodName;
        } else {
            temp = method.getReturnType().toString();
            methodName = method.getName();
            if (temp.contains("<") && temp.contains(">")) {
                temp = temp.substring(0, temp.indexOf("<")) + temp.substring(temp.lastIndexOf(">") + 1, temp.length());
            }
            temp = temp + "_" + methodName;
        }

        StringBuilder str = new StringBuilder(temp + "(");
        List<Type> pars = method.getParameterTypes();

        for (int i = 0; i < pars.size(); i++) {
            String tempParameter = pars.get(i).toString();
            if (tempParameter.contains("<") && tempParameter.contains(">")) {
                tempParameter = tempParameter.substring(0, tempParameter.indexOf("<"))
                        + tempParameter.substring(tempParameter.lastIndexOf(">") + 1, tempParameter.length());
            }
            str.append(tempParameter);

            if (i != (pars.size() - 1))
                str.append(",");
        }
        str.append(")");
        return str.toString();
    }
}
