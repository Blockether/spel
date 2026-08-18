## DELETE http://localhost:39991/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 18 Aug 2026 08:15:51 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:39991/echo' \
  -X DELETE
```
