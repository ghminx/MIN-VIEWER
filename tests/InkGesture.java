import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.Method;

public class InkGesture {
    public static void main(String[] args) throws Exception {
        Class<?> cls=Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager=cls.getMethod("getInstance").invoke(null);
        Method inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class);
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;
        p.toolType=args[0].equals("pen")?MotionEvent.TOOL_TYPE_STYLUS:MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.pressure=.7f;c.size=1;
        float x=Float.parseFloat(args[1]), y=Float.parseFloat(args[2]);
        float endX=Float.parseFloat(args[3]),endY=Float.parseFloat(args[4]);
        long down=SystemClock.uptimeMillis();
        for(int i=0;i<=20;i++) {
            c.x=x+(endX-x)*i/20;c.y=y+(endY-y)*i/20;
            MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),i==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_MOVE,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,p.toolType==MotionEvent.TOOL_TYPE_STYLUS?InputDevice.SOURCE_STYLUS:InputDevice.SOURCE_TOUCHSCREEN,0);
            if(!(Boolean)inject.invoke(manager,e,2))throw new IllegalStateException("입력 실패");e.recycle();SystemClock.sleep(20);
        }
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),args.length>5?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,p.toolType==MotionEvent.TOOL_TYPE_STYLUS?InputDevice.SOURCE_STYLUS:InputDevice.SOURCE_TOUCHSCREEN,0);
        if(!(Boolean)inject.invoke(manager,e,2))throw new IllegalStateException("입력 종료 실패");e.recycle();
    }
}
