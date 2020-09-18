package daos

import (
	"context"
	"github.com/singerdmx/BulletJournal/daemon/clients"
	"github.com/singerdmx/BulletJournal/daemon/daos/models"
	"github.com/singerdmx/BulletJournal/daemon/logging"
)

type GroupDao struct {
	Ctx context.Context
	pgc *clients.PostgresClient
	log *logging.Logger
}

var groupDao *GroupDao

func (g *GroupDao) SetLogger() {
	g.log = logging.GetLogger()
}

func (g *GroupDao) SetClient() {
	g.pgc = clients.GetPostgresClient()
}

func (g *GroupDao) Initialize () {
	if g.log == nil {
		g.SetLogger()
	}
	if g.pgc == nil {
		g.SetClient()
	}
}

func (g *GroupDao) FindGroup(groupId uint64) *models.Group {
	var group *models.Group
	g.pgc.GetClient().Where("id = ?", groupId).First(&group)
	return group
}

func GetGroupDao() *GroupDao {
	if groupDao.pgc == nil || groupDao.log == nil {
		groupDao.Initialize()
	}
	return groupDao
}

func Find(groupId uint64) *models.Group {
	return GetGroupDao().FindGroup(groupId)
}