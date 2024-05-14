package com.openpositioning.PositionMe.fragments;

/**
 * Provides a collection of static methods for common matrix operations,
 * including addition, subtraction, multiplication, and finding the inverse of a matrix.
 * @author Apoorv Tewari
 */
public class MatrixOperations {

    /**
     * Adds two matrices together.
     * @param A First matrix.
     * @param B Second matrix.
     * @return The result of adding A and B.
     */
    public static double[][] add(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] result = new double[rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = A[i][j] + B[i][j];
            }
        }

        return result;
    }

    /**
     * Subtracts matrix B from matrix A.
     * @param A First matrix.
     * @param B Second matrix.
     * @return The result of subtracting B from A.
     */
    public static double[][] subtract(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] result = new double[rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = A[i][j] - B[i][j];
            }
        }

        return result;
    }

    /**
     * Subtracts vector b from vector a.
     * @param a First vector.
     * @param b Second vector.
     * @return The result of subtracting b from a.
     */
    public static double[] subtractVectors(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions must match for subtraction");
        }

        double[] result = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] - b[i];
        }
        return result;
    }

    /**
     * Adds two vectors together.
     * @param a First vector.
     * @param b Second vector.
     * @return The result of adding a and b.
     */
    public static double[] addVectors(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions must match for addition");
        }

        double[] result = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] + b[i];
        }
        return result;
    }

    /**
     * Multiplies a matrix by a vector.
     * @param matrix The matrix.
     * @param vector The vector.
     * @return The result of multiplying the matrix by the vector.
     */
    public static double[] multiplyMatrixAndVector(double[][] matrix, double[] vector) {
        if (matrix[0].length != vector.length) {
            throw new IllegalArgumentException("Matrix columns and vector size must match for multiplication");
        }

        double[] result = new double[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < vector.length; j++) {
                result[i] += matrix[i][j] * vector[j];
            }
        }
        return result;
    }

    /**
     * Multiplies two matrices together.
     * @param A First matrix.
     * @param B Second matrix.
     * @return The result of multiplying A by B.
     */
    public static double[][] multiply(double[][] A, double[][] B) {
        int aRows = A.length;
        int aCols = A[0].length;
        int bCols = B[0].length;
        double[][] result = new double[aRows][bCols];

        for (int i = 0; i < aRows; i++) {
            for (int j = 0; j < bCols; j++) {
                for (int k = 0; k < aCols; k++) {
                    result[i][j] += A[i][k] * B[k][j];
                }
            }
        }

        return result;
    }

    /**
     * Transposes a matrix.
     * @param matrix The matrix to transpose.
     * @return The transposed matrix.
     */
    public static double[][] transpose(double[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[][] transposed = new double[cols][rows];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                transposed[j][i] = matrix[i][j];
            }
        }

        return transposed;
    }

    /**
     * Finds the inverse of a square matrix using Gaussian elimination.
     * @param matrix The matrix to invert.
     * @return The inverse of the matrix.
     */
    public static double[][] inverse(double[][] matrix) {
        int n = matrix.length;
        double[][] x = new double[n][n];
        double[][] b = new double[n][n];
        int[] index = new int[n];
        for (int i = 0; i < n; ++i) b[i][i] = 1;

        // Transform the matrix into an upper triangle
        gaussian(matrix, index);

        // Update the matrix b[i][j] with the ratios stored
        for (int i = 0; i < n - 1; ++i)
            for (int j = i + 1; j < n; ++j)
                for (int k = 0; k < n; ++k)
                    b[index[j]][k] -= matrix[index[j]][i] * b[index[i]][k];

        // Perform backward substitutions
        for (int i = 0; i < n; ++i) {
            x[n - 1][i] = b[index[n - 1]][i] / matrix[index[n - 1]][n - 1];
            for (int j = n - 2; j >= 0; --j) {
                x[j][i] = b[index[j]][i];
                for (int k = j + 1; k < n; ++k) {
                    x[j][i] -= matrix[index[j]][k] * x[k][i];
                }
                x[j][i] /= matrix[index[j]][j];
            }
        }
        return x;
    }

    /**
     * Performs partial-pivoting Gaussian elimination on a matrix.
     * @param matrix The matrix to be processed.
     * @param index The array that stores the order of pivoting.
     */
    public static void gaussian(double[][] matrix, int[] index) {
        int n = index.length;
        double[] c = new double[n]; // Scaling factor

        // Initialize the index
        for (int i = 0; i < n; ++i) index[i] = i;

        // Find the rescaling factors, one from each row
        for (int i = 0; i < n; ++i) {
            double c1 = 0;
            for (int j = 0; j < n; ++j) {
                double c0 = Math.abs(matrix[i][j]);
                if (c0 > c1) c1 = c0;
            }
            c[i] = c1;
        }

        // Search the pivoting element from each column
        int k = 0;
        for (int j = 0; j < n - 1; ++j) {
            double pi1 = 0;
            for (int i = j; i < n; ++i) {
                double pi0 = Math.abs(matrix[index[i]][j]);
                pi0 /= c[index[i]];
                if (pi0 > pi1) {
                    pi1 = pi0;
                    k = i;
                }
            }

            // Interchange rows according to the pivoting order
            int itmp = index[j];
            index[j] = index[k];
            index[k] = itmp;
            for (int i = j + 1; i < n; ++i) {
                double pj = matrix[index[i]][j] / matrix[index[j]][j];

                // Record pivoting ratios below the diagonal
                matrix[index[i]][j] = pj;

                // Modify other elements accordingly
                for (int l = j + 1; l < n; ++l)
                    matrix[index[i]][l] -= pj * matrix[index[j]][l];
            }
        }
    }

    /**
     * Generates an identity matrix of a given size.
     * @param size The size of the identity matrix.
     * @return An identity matrix of the specified size.
     */
    public static double[][] identityMatrix(int size) {
        double[][] identity = new double[size][size];

        for (int i = 0; i < size; i++) {
            identity[i][i] = 1;
        }

        return identity;
    }
}
