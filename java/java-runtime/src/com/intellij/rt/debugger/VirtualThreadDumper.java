// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.rt.debugger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;


@SuppressWarnings("unchecked")
public final class VirtualThreadDumper {

  static volatile boolean initialized = false;
  static boolean successfully = false;

  static MethodHandle streamIteratorHandle;

  static MethodHandle containersRootHandle;
  static MethodHandle containerChildrenHandle;
  static MethodHandle containerThreadsHandle;

  static MethodHandle threadIsVirtualHandle;
  static MethodHandle threadThreadState;

  private static boolean init(MethodHandles.Lookup lookup) {
    if (!initialized) {
      try {
        Class<?> streamClass = Class.forName("java.util.stream.Stream");
        streamIteratorHandle = lookup.findVirtual(streamClass, "iterator", MethodType.methodType(Iterator.class));

        Class<?> threadContainersClass = Class.forName("jdk.internal.vm.ThreadContainers");
        Class<?> threadContainerClass = Class.forName("jdk.internal.vm.ThreadContainer");
        containersRootHandle = lookup.findStatic(threadContainersClass, "root", MethodType.methodType(threadContainerClass));
        containerChildrenHandle = lookup.findVirtual(threadContainerClass, "children", MethodType.methodType(streamClass));
        containerThreadsHandle = lookup.findVirtual(threadContainerClass, "threads", MethodType.methodType(streamClass));

        //noinspection JavaLangInvokeHandleSignature
        threadIsVirtualHandle = lookup.findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
        //noinspection JavaLangInvokeHandleSignature
        threadThreadState = lookup.findVirtual(Thread.class, "threadState", MethodType.methodType(Thread.State.class));

        successfully = true;
      } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
        successfully = false;
      }
      initialized = true;
    }
    return successfully;
  }

  /**
   * Returns all virtual threads with stack traces (along with a name and thread state) and parallel array of thread IDs.
   * <br/>
   * They are grouped by equal stack traces and packed into the plain `Object` array in the following way:
   * <ul>
   *   <li>First, there is the stack trace object as `String`.</li>
   *   <li>Then, there are one or many thread objects as `Thread` references and each of them has the above stack trace.</li>
   *   <li>After the last thread object, there is a single `null` as a delimiter.</li>
   *   <li>Then we have a new group of stack trace and threads, or the array ends.</li>
   * </ul>
   * <br/>
   * Returns {@code null} if there are no virtual threads or some error occurred.
   */
  public static Object[] getAllVirtualThreadsWithStackTraces(MethodHandles.Lookup lookup) throws Throwable {
    if (!init(lookup)) {
      return null;
    }

    ArrayList<Thread> threads = getAllVirtualThreads(lookup);
    if (threads.isEmpty()) {
      return null;
    }

    HashMap<String, ArrayList<Thread>> groupedByStackTrace = new HashMap<>();
    for (Thread t : threads) {
      StringBuilder buffer = new StringBuilder();

      // "Stack trace" format, in such a way it should be shared between multiple threads and easily processed on the debugger side:
      // <name>
      // <javaThreadState>
      // <stack trace elements...>

      String name = t.getName();
      Thread.State javaThreadState = (Thread.State)threadThreadState.invoke(t);
      buffer.append(name).append('\n').append(javaThreadState);

      for (StackTraceElement ste : t.getStackTrace()) {
        buffer.append("\n\tat ").append(ste);
      }
      String stackTrace = buffer.toString();

      ArrayList<Thread> similarThreads = groupedByStackTrace.get(stackTrace);
      if (similarThreads == null) {
        similarThreads = new ArrayList<>();
        groupedByStackTrace.put(stackTrace, similarThreads);
      }
      similarThreads.add(t);
    }

    long[] tids = new long[threads.size()];
    int tidIdx = 0;

    Object[] allStackTraceAndThreads = new Object[threads.size() + groupedByStackTrace.size() * 2];
    int stIdx = 0;

    for (Map.Entry<String, ArrayList<Thread>> e : groupedByStackTrace.entrySet()) {
      String st = e.getKey();
      ArrayList<Thread> ts = e.getValue();
      allStackTraceAndThreads[stIdx++] = st;
      for (Thread t : ts) {
        allStackTraceAndThreads[stIdx++] = t;
        tids[tidIdx++] = t.getId();
      }
      allStackTraceAndThreads[stIdx++] = null;
    }
    assert stIdx == allStackTraceAndThreads.length;

    return new Object[] { allStackTraceAndThreads, tids };
  }

  private static ArrayList<Thread> getAllVirtualThreads(MethodHandles.Lookup lookup) throws Throwable {
    if (!init(lookup)) return null;

    ArrayList<Thread> result = new ArrayList<>();
    for (Object container : getAllContainers()) {
      Object /*Stream<Thread>*/ threads = containerThreadsHandle.invoke(container);
      Iterator<Thread> it = (Iterator<Thread>)streamIteratorHandle.invoke(threads);
      while (it.hasNext()) {
        Thread t = it.next();
        boolean isVirtual = (boolean) threadIsVirtualHandle.invoke(t);
        if (isVirtual) {
          result.add(t);
        }
      }
    }
    return result;
  }

  private static ArrayList<Object> getAllContainers() throws Throwable {
    ArrayList<Object> allContainers = new ArrayList<>();
    Object rootContainer = containersRootHandle.invoke();
    collectContainers(allContainers, rootContainer);
    return allContainers;
  }

  private static void collectContainers(ArrayList<Object> allContainers, Object container) throws Throwable {
    allContainers.add(container);
    Object/*Stream<ThreadContainer>*/ children = containerChildrenHandle.invoke(container);
    Iterator<Object> it = (Iterator<Object>)streamIteratorHandle.invoke(children);
    while (it.hasNext()) {
      Object/*ThreadContainer*/ child = it.next();
      collectContainers(allContainers, child);
    }
  }
}
