## PATCH http://localhost:40379/echo → 200 OK

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Tue, 15 Sep 2026 13:25:24 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:40379/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
