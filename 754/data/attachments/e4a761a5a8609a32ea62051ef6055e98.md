## DELETE http://localhost:35103/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 15 Sep 2026 12:11:18 GMT
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
curl 'http://localhost:35103/echo' \
  -X DELETE
```
