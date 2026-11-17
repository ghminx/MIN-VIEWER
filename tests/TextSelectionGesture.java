import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.Method;

/** 길게 누른 뒤 드래그하는 실제 터치 선택 시험 입력. */
public class TextSelectionGesture {
    public static void main(String[] args) throws Exception {
        Class<?> cls = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = cls.getMethod("getInstance").invoke(null);
        Method inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
        float x = Float.parseFloat(args[0]), y = Float.parseFloat(args[1]);
        float endX = Float.parseFloat(args[2]), endY = Float.parseFloat(args[3]);
        long down = SystemClock.uptimeMillis();
        for (int step = 0; step <= 21; step++) {
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 21 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            float t = Math.min(step, 20) / 20f;
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties(); p.id = 0; p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords(); c.x = x + (endX-x)*t; c.y = y + (endY-y)*t; c.pressure = 1; c.size = 1;
            MotionEvent e = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
            if (!(Boolean) inject.invoke(manager,e,2)) throw new IllegalStateException("선택 입력 실패");
            e.recycle(); SystemClock.sleep(step == 0 ? 1600 : 30);
        }
    }
}
