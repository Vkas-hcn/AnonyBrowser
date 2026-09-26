package androidx.core.app;

public abstract class WebCanService extends JobIntentService {
    @Override
    GenericWorkItem dequeueWork() {
        try {
            return super.dequeueWork();
        }catch (Exception e){
            return null;
        }
    }
}
