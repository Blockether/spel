## DELETE http://localhost:37329/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 04 Aug 2026 15:04:25 GMT
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
curl 'http://localhost:37329/echo' \
  -X DELETE
```
