package com.openpositioning.PositionMe.fragments;

import com.openpositioning.PositionMe.PdrProcessing;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import android.content.Intent;


import com.openpositioning.PositionMe.R;

public class Kcali extends AppCompatActivity {

    // UI elements
    private SharedPreferences settings;
    private EditText inputStepLength;
    private Button kcalibutton;

    // Preset values (modify as needed)
    public float presentK;
    public float estimatedStrideLength;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_correction);  // 确保布局文件名称正确
        this.settings = PreferenceManager.getDefaultSharedPreferences(this);

        // 初始化布局中的控件
        inputStepLength = findViewById(R.id.inputStepLength);
        kcalibutton = findViewById(R.id.kcalibutton);

        // 设置按钮点击事件，调用校准方法
        kcalibutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d("Kcali", "Button clicked.");
                //calibrateK();
            }
        });
    }
}

    /**
     * 从 EditText 获取实际步长，计算校准后的 k 值，并通过 Log 输出结果。
     */
//    private void calibrateK() {
//        // 获取并修剪用户输入
//        String input = inputStepLength.getText().toString().trim();
//
//        if (input.isEmpty()) {
//            Log.d("Kcali", "Please enter the actual step length.");
//            return;
//        }
//
//        presentK = getPresentK();
//        estimatedStrideLength = getAvgStepLength();
//
//        try {
//            // 将用户输入转换为 float 值
//            float actualStepLength = Float.parseFloat(input);
//
//            // 检查除数是否为 0（这里判断的是 estimatedStrideLength）
//            if (estimatedStrideLength == 0) {
//                Log.d("Kcali", "Estimated stride length is zero, cannot calibrate.");
//                return;
//            }
//
//            // 计算新的校准后的 k 值
//            float newK = presentK * (actualStepLength / estimatedStrideLength);
//
//            // 通过 Log 输出校准后的 k 值
//            Log.d("Kcali", String.format("Calibrated k: %.4f", newK));
//        } catch (NumberFormatException e) {
//            Log.e("Kcali", "Invalid input. Please enter a valid number.");
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * 从 SharedPreferences 中获取当前的 k 值（字符串转换为 float）。
//     */
//    private float getPresentK() {
//        return Float.parseFloat(settings.getString("weiberg_k", "0.243"));
//    }
//
//    /**
//     * 通过创建 PdrProcessing 实例获取平均步长。
//     * 注意：如果每次都新建实例，可能得不到累计的步长数据。
//     */
//    private float getAvgStepLength() {
//        PdrProcessing pdrProcessing = new PdrProcessing(this);
//        return pdrProcessing.getAverageStepLength();
//    }
//}
