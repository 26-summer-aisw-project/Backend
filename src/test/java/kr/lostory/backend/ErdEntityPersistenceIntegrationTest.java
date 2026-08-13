package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import kr.lostory.backend.audit.domain.AuditLog;
import kr.lostory.backend.audit.domain.AuditLogRepository;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.ItemFeature;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;
import kr.lostory.backend.founditem.domain.ItemFeatureRepository;
import kr.lostory.backend.founditem.domain.ItemFeatureSource;
import kr.lostory.backend.founditem.domain.ItemFeatureVisibility;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.lostcenter.domain.LostCenter;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import kr.lostory.backend.lostreport.domain.LostReportStatus;
import kr.lostory.backend.lostreport.domain.MatchCandidate;
import kr.lostory.backend.lostreport.domain.MatchCandidateRepository;
import kr.lostory.backend.lostreport.domain.ReportWaypoint;
import kr.lostory.backend.lostreport.domain.ReportWaypointRepository;
import kr.lostory.backend.point.domain.CandidateAccess;
import kr.lostory.backend.point.domain.CandidateAccessRepository;
import kr.lostory.backend.point.domain.PointAccount;
import kr.lostory.backend.point.domain.PointAccountRepository;
import kr.lostory.backend.point.domain.PointEntryType;
import kr.lostory.backend.point.domain.PointLedger;
import kr.lostory.backend.point.domain.PointLedgerRepository;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest
@Transactional
class ErdEntityPersistenceIntegrationTest {

	private static final String HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5n33S5U9P4XQxG1VVDzI7kVxwZKXgOe";
	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(null, 4326);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private FoundItemRepository foundItemRepository;

	@Autowired
	private LostCenterRepository lostCenterRepository;

	@Autowired
	private ItemFeatureRepository itemFeatureRepository;

	@Autowired
	private LostReportRepository lostReportRepository;

	@Autowired
	private ReportWaypointRepository reportWaypointRepository;

	@Autowired
	private MatchCandidateRepository matchCandidateRepository;

	@Autowired
	private PointAccountRepository pointAccountRepository;

	@Autowired
	private PointLedgerRepository pointLedgerRepository;

	@Autowired
	private CandidateAccessRepository candidateAccessRepository;

	@Autowired
	private AuditLogRepository auditLogRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void erdEntitiesPersistWithTheirRelationshipsAndConstraints() {
		Instant now = Instant.now();
		User user = userRepository.saveAndFlush(new User("erd-" + UUID.randomUUID() + "@example.test", HASH, "신고자"));
		LostCenter center = lostCenterRepository.saveAndFlush(new LostCenter(
				"directory:campus-center",
				"캠퍼스 분실물 센터",
				"서울특별시 동작구 상도로 369",
				point(126.957, 37.496),
				"02-820-0000",
				"평일 09:00-18:00"
		));
		FoundItem item = foundItemRepository.saveAndFlush(new FoundItem(
				user.getId(),
				"검은 지갑",
				"WALLET",
				"검은색 카드 지갑",
				now,
				"중앙도서관",
				StorageMethod.HANDED_TO_CENTER,
				null,
				center.getName()
		));
		ItemFeature feature = itemFeatureRepository.saveAndFlush(new ItemFeature(
				item.getId(),
				ItemFeatureKind.COLOR,
				"검은색",
				(short) 1,
				ItemFeatureSource.FINDER,
				ItemFeatureVisibility.MATCH_ONLY,
				null
		));
		LostReport report = lostReportRepository.saveAndFlush(new LostReport(
				user.getId(),
				"WALLET",
				now.minusSeconds(3_600),
				now,
				"검은색 카드 지갑을 잃어버렸습니다.",
				1_000,
				now.plusSeconds(604_800)
		));
		ReportWaypoint waypoint = reportWaypointRepository.saveAndFlush(new ReportWaypoint(
				report.getId(),
				(short) 1,
				"중앙도서관",
				point(126.957, 37.496)
		));
		MatchCandidate candidate = matchCandidateRepository.saveAndFlush(new MatchCandidate(
				report.getId(),
				item.getId(),
				(short) 1,
				new BigDecimal("92.50"),
				"{}"
		));
		PointAccount account = pointAccountRepository.saveAndFlush(new PointAccount(user.getId()));
		PointLedger ledger = pointLedgerRepository.saveAndFlush(new PointLedger(
				user.getId(),
				PointEntryType.DEMO_GRANT,
				100,
				UUID.randomUUID(),
				null
		));
		CandidateAccess access = candidateAccessRepository.saveAndFlush(new CandidateAccess(
				report.getId(),
				user.getId(),
				ledger.getId()
		));
		AuditLog auditLog = auditLogRepository.saveAndFlush(new AuditLog(
				user.getId(),
				"LOST_REPORT_CREATED",
				"LOST_REPORT",
				report.getId(),
				"{}"
		));
		entityManager.clear();

		assertThat(userRepository.findById(user.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
		assertThat(lostCenterRepository.findById(center.getId()).orElseThrow().getLocation().getSRID()).isEqualTo(4326);
		assertThat(itemFeatureRepository.findById(feature.getId())).isPresent();
		assertThat(lostReportRepository.findById(report.getId()).orElseThrow().getStatus()).isEqualTo(LostReportStatus.OPEN);
		assertThat(reportWaypointRepository.findById(waypoint.getId()).orElseThrow().getLocation().getSRID()).isEqualTo(4326);
		assertThat(matchCandidateRepository.findById(candidate.getId())).isPresent();
		assertThat(pointAccountRepository.findById(account.getUserId())).isPresent();
		assertThat(pointLedgerRepository.findById(ledger.getId())).isPresent();
		assertThat(candidateAccessRepository.findById(access.getId())).isPresent();
		assertThat(auditLogRepository.findById(auditLog.getId())).isPresent();
		assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('public.user_roles')", String.class)).isNull();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT ST_SRID(location::geometry) FROM lost_centers WHERE id = ?",
				Integer.class,
				center.getId()
		)).isEqualTo(4326);
	}

	private static Point point(double longitude, double latitude) {
		return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
	}
}
