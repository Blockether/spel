## PUT http://localhost:33417/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Tue, 20 Oct 2026 21:23:29 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:33417/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
