## GET http://localhost:42089/echo?name=Alice&role=Engineer&team=Platform → 200 OK

### Request Headers
```
GET http://localhost:42089/echo?name=Alice&role=Engineer&team=Platform
```

### Response Headers
```
content-length: 80
content-type: application/json
date: Thu, 09 Apr 2026 18:15:20 GMT
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
curl 'http://localhost:42089/echo?name=Alice&role=Engineer&team=Platform'
```
