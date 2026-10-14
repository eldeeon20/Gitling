package me.sheimi.sgit.repo.tasks.repo;

import com.manichord.mgit.auth.RepoCredentials;

import me.sheimi.android.activities.SheimiFragmentActivity;
import me.sheimi.sgit.database.models.Repo;

/**
 * Super class for Tasks that operate on a git remote
 */

public abstract class RepoRemoteOpTask extends RepoOpTask implements SheimiFragmentActivity.OnPasswordEntered {


    public RepoRemoteOpTask(Repo repo) {
        super(repo);
    }


    @Override
    public void onClicked(String username, String password, boolean savePassword) {
        // Remembered for the host of the remote that asked (not the whole repo), so they're not
        // sent to the repo's other remotes on other hosts (#87)
        RepoCredentials.INSTANCE.remember(mRepo, mCredentialRemoteUrl, username, password, savePassword);

        mRepo.removeTask(this);
        getNewTask().executeTask();
    }

    @Override
    public void onCanceled() {

    }

    public abstract RepoRemoteOpTask getNewTask();
}
