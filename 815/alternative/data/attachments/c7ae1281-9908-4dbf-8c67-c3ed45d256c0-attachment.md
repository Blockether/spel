## DELETE http://localhost:38681/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 03 Nov 2026 22:23:54 GMT
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
curl 'http://localhost:38681/echo' \
  -X DELETE
```
