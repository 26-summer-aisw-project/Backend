package kr.lostory.backend.lostreport.application;

import kr.lostory.backend.lostreport.domain.LostReportRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MatchingPolicyStartupInvalidator implements ApplicationRunner {

	private final LostReportRepository reportRepository;

	public MatchingPolicyStartupInvalidator(LostReportRepository reportRepository) {
		this.reportRepository = reportRepository;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments arguments) {
		reportRepository.markOpenCandidatesStale();
	}
}
