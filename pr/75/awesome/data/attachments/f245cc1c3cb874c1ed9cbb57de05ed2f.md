## GET http://localhost:42763/echo → 200 OK

### Request Headers
```
GET http://localhost:42763/echo
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Tue, 03 Mar 2026 09:51:48 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "name=Alice&role=Engineer&team=Platform"
}
```

### cURL
```bash
curl 'http://localhost:42763/echo'
```
