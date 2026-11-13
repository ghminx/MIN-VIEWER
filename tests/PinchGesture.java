import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

public class PinchGesture {
    public static void main(String[] args) throws Exception {
        float x = Float.parseFloat(args[0]);
        float y = Float.parseFloat(args[1]);
        float start = Float.parseFloat(args[2]);
        float end = Float.parseFloat(args[3]);
        Class<?> managerClass = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = managerClass.getMethod("getInstance").invoke(null);
        Method inject = managerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].pressure = 1;
            coords[i].size = 1;
            coords[i].y = y;
        }
        long down = SystemClock.uptimeMillis();
        coords[0].x = x - start / 2;
        coords[1].x = x + start / 2;
        send(inject, manager, down, MotionEvent.ACTION_DOWN, 1, properties, coords);
        send(inject, manager, down, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords);
        for (int i = 1; i <= 20; i++) {
            float distance = start + (end - start) * i / 20;
            coords[0].x = x - distance / 2;
            coords[1].x = x + distance / 2;
            SystemClock.sleep(25);
            send(inject, manager, down, MotionEvent.ACTION_MOVE, 2, properties, coords);
        }
        if (args.length > 4) SystemClock.sleep(Long.parseLong(args[4]));
        send(inject, manager, down, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords);
        send(inject, manager, down, MotionEvent.ACTION_UP, 1, properties, coords);
    }

    private static void send(Method inject, Object manager, long down, int action, int count,
                             MotionEvent.PointerProperties[] properties, MotionEvent.PointerCoords[] coords) throws Exception {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        if (!(Boolean) inject.invoke(manager, event, 2)) throw new IllegalStateException("Touch injection failed");
        event.recycle();
    }
}
