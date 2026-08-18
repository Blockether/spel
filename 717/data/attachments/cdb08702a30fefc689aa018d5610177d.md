## PUT http://localhost:39991/echo → 200 OK

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
date: Tue, 18 Aug 2026 08:15:51 GMT
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
curl 'http://localhost:39991/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
