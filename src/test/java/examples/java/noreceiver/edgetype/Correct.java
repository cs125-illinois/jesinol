package examples.java.noreceiver.edgetype;

import edu.illinois.cs.cs125.jenisol.core.EdgeType;

public class Correct {
  @EdgeType private static final int[] EDGE = new int[] {8888, 888888};

  public static boolean value(int first) {
    return first == 8888 || first == 888888;
  }
}
