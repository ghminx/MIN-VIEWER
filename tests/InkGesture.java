import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.Method;

public class InkGesture {
    public static void main(String[] args) throws Exception {
        Class<?> cls=Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager=cls.getMethod("getInstance").invoke(null);
        Method inject=cls.getMethod("injectInputEvent",InputEvent.class,int.class);
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;
        p.toolType=args[0].equals("finger")?MotionEvent.TOOL_TYPE_FINGER:MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.pressure=.7f;c.size=1;
        float x=Float.parseFloat(args[1]), y=Float.parseFloat(args[2]);
        float endX=Float.parseFloat(args[3]),endY=Float.parseFloat(args[4]);
        boolean box=args.length>5 && args[5].startsWith("box");
        boolean triangle=args.length>5 && args[5].equals("triangle");
        boolean cancel=args.length>5 && args[5].endsWith("cancel");
        boolean button=args.length>5 && args[5].startsWith("button");
        if(args.length>5&&args[5].equals("double-tap")) {
            c.x=x;c.y=y;
            for(int tap=0;tap<2;tap++) {
                long down=SystemClock.uptimeMillis();
                for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
                    MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
                    if(!(Boolean)inject.invoke(manager,e,2))throw new IllegalStateException("두 번 탭 입력 실패");e.recycle();SystemClock.sleep(40);
                }
            }
            return;
        }
        if(args.length>5&&args[5].equals("button-tap")) {
            c.x=x;c.y=y;long time=SystemClock.uptimeMillis();
            for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_BUTTON_PRESS,MotionEvent.ACTION_UP}) {
                MotionEvent e=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,action==MotionEvent.ACTION_DOWN?0:MotionEvent.BUTTON_STYLUS_PRIMARY,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
                if(action==MotionEvent.ACTION_BUTTON_PRESS)MotionEvent.class.getMethod("setActionButton",int.class).invoke(e,MotionEvent.BUTTON_STYLUS_PRIMARY);
                if(!(Boolean)inject.invoke(manager,e,2))throw new IllegalStateException("접촉 중 펜 버튼 입력 실패");e.recycle();SystemClock.sleep(30);
            }
            return;
        }
        if(args[0].startsWith("hover")) {
            c.x=x;c.y=y;
            int action=args[0].equals("hover-exit")?MotionEvent.ACTION_HOVER_EXIT:MotionEvent.ACTION_HOVER_MOVE;
            MotionEvent e=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,button?MotionEvent.BUTTON_STYLUS_PRIMARY:0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
            if(!(Boolean)inject.invoke(manager,e,2))throw new IllegalStateException("펜 호버 입력 실패");e.recycle();return;
        }
        float[][] corners=box?new float[][]{{x,y},{endX,y},{endX,endY},{x,endY},{x,y}}:
            triangle?new float[][]{{x,y},{endX,y},{x,endY},{x,y}}:new float[][]{{x,y},{endX,endY}};
        int steps=(corners.length-1)*20;
        long down=SystemClock.uptimeMillis();
        for(int i=0;i<=steps;i++) {
            int side=Math.min(i/20,corners.length-2);
            float t=i==steps?1:(i%20)/20f;
            c.x=corners[side][0]+(corners[side+1][0]-corners[side][0])*t;
            c.y=corners[side][1]+(corners[side+1][1]-corners[side][1])*t;
            boolean held=button;
            if(button&&args[5].startsWith("button-release"))held=i<steps/2;
            if(button&&args[5].startsWith("button-press"))held=i>=steps/2;
            MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),i==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_MOVE,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,held?MotionEvent.BUTTON_STYLUS_PRIMARY:0,1,1,0,0,p.toolType==MotionEvent.TOOL_TYPE_STYLUS?InputDevice.SOURCE_STYLUS:InputDevice.SOURCE_TOUCHSCREEN,0);
            if(!(Boolean)inject.invoke(manager,e,2))throw new IllegalStateException("입력 실패");e.recycle();SystemClock.sleep(20);
        }
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,p.toolType==MotionEvent.TOOL_TYPE_STYLUS?InputDevice.SOURCE_STYLUS:InputDevice.SOURCE_TOUCHSCREEN,0);
        if(!(Boolean)inject.invoke(manager,e,2))throw new IllegalStateException("입력 종료 실패");e.recycle();
    }
}
